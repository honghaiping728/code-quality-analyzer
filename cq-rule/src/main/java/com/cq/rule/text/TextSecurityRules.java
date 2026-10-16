package com.cq.rule.text;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.lang.Language;
import com.cq.rule.CredentialPatterns;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全维度规则（文本扫描版，5 条）
 * <p>
 * 这一组针对的是**非 Java 文件**里最典型的泄露与危险写法：明文口令写进
 * {@code application.yml}、私钥被提交进仓库、CI 脚本里 {@code curl | sh}、
 * 容器以 root 运行。它们在语法上没有任何歧义，用不上数据流分析，
 * 恰好适合行扫描。
 * <p>
 * 语言范围刻意收窄。凭证类规则只作用于 YAML / properties / TOML / Dockerfile：
 * 一旦放开到 JSON，{@code package.json}、各种 lock 文件会带来大量噪音；
 * 放开到源码文件，则会把 {@code token} 这一类的变量名误当成凭证。
 */
public final class TextSecurityRules {

    private TextSecurityRules() {}

    /** 全部文本安全规则 */
    public static List<Rule> all() {
        return List.of(
                new ConfigPlaintextSecretRule(),
                new PrivateKeyMaterialRule(),
                new InsecureHttpEndpointRule(),
                new ShellDangerousCommandRule(),
                new DockerRunAsRootRule());
    }

    // ==================================================================
    // 1. 配置文件明文凭证
    // ==================================================================
    static final class ConfigPlaintextSecretRule extends AbstractTextRule {

        /** 只在这些「键值对形态」的配置语言上生效 */
        private static final Set<Language> SCOPED = EnumSet.of(
                Language.YAML, Language.PROPERTIES, Language.TOML, Language.DOCKERFILE);

        /** 键值对：可选的列表符号 -，键，分隔符 : 或 =，值 */
        private static final Pattern KEY_VALUE = Pattern.compile(
                "^\\s*-?\\s*([A-Za-z0-9_.\\-\\[\\]]+)\\s*[:=]\\s*(\\S.*)$");

        /** Dockerfile 的 ENV/ARG 声明：ENV DB_PASSWORD=xxx */
        private static final Pattern DOCKER_KEYWORD = Pattern.compile("^(ENV|ARG)\\s+(.+)$");

        /**
         * 键名以这些词收尾时，值描述的是「凭证放在哪 / 怎么处理」而非凭证本身
         * <p>
         * {@code secret-key-location}、{@code token-timeout} 这类键名会被凭证名模式命中，
         * 但它们的值是一个路径或一个时长。刻意不含 {@code key}：{@code api-key}、
         * {@code private-key} 的值恰恰就是凭证本身。
         */
        private static final Pattern NON_SECRET_KEY = Pattern.compile(
                "(?i).*[-_.](url|uri|endpoint|host|port|type|algorithm|algo|encoder|location|path|"
                        + "file|header|name|length|expiry|expire|timeout|duration|ttl|issuer|audience|"
                        + "count|mode|factory|class|enabled|required|prefix|suffix|format|field)s?$");

        /** 示例文件里的值本来就都是假的 */
        private static final Pattern EXAMPLE_FILE = Pattern.compile(
                "(?i).*\\.(example|sample|template|dist|bak)(\\..*)?$");

        /** 值不可能是凭证的形态：数字、布尔、时长、路径、URL、限定类名 */
        private static final Pattern NUMBER_LIKE = Pattern.compile("[-+]?\\d+(\\.\\d+)?[A-Za-z%]{0,3}");
        private static final Pattern BOOLEAN_LIKE = Pattern.compile("(?i)(true|false|yes|no|on|off|null)");
        private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

        ConfigPlaintextSecretRule() {
            super("SEC.CONFIG_PLAINTEXT_SECRET", "配置文件明文凭证", IssueType.SECURITY,
                    Severity.BLOCKER, 0.7,
                    "配置文件中直接写入了口令、密钥一类的凭证，会随代码库一同泄露（检查 YAML / properties / TOML / Dockerfile）",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            if (EXAMPLE_FILE.matcher(ctx.filePath() == null ? "" : ctx.filePath()).matches()) {
                return List.of();   // .env.example 一类的示例文件，值本来就是占位
            }
            List<Issue> issues = new ArrayList<>();
            List<String> lines = lines(ctx);
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String raw = lines.get(index);
                if (isBlankOrComment(ctx, raw)) {
                    continue;
                }
                // 必须先去掉行尾注释，否则 "password: xyz  # 说明" 的值会连注释一起被当成凭证
                String line = stripInlineComment(ctx, raw);
                if (ctx.language() == Language.DOCKERFILE) {
                    Matcher keyword = DOCKER_KEYWORD.matcher(line.strip());
                    if (keyword.matches()) {
                        line = keyword.group(2);   // ENV 前缀不属于键值对本身
                    }
                }
                Matcher matcher = KEY_VALUE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String key = matcher.group(1);
                String value = unquote(matcher.group(2).trim());
                if (!CredentialPatterns.CREDENTIAL_NAME.matcher(key).matches()
                        || NON_SECRET_KEY.matcher(key).matches()) {
                    continue;
                }
                if (!looksLikeSecret(value)) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "配置项 " + key + " 直接写入了明文字符串凭证",
                        "改为引用环境变量（如 ${DB_PASSWORD}）或从配置中心读取，并轮换已泄露的凭证"));
            }
            return issues;
        }

        /** 去掉包裹的引号 */
        private static String unquote(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return value.substring(1, value.length() - 1).trim();
                }
            }
            return value;
        }

        /** 值是否真的像凭证：够长、非占位、且不属于一眼可排除的形态 */
        private static boolean looksLikeSecret(String value) {
            // 长度下限比源码版更严：配置值短到 6 位以下基本是环境名、开关值一类的字段
            if (value.length() < 6 || CredentialPatterns.isPlaceholder(value)) {
                return false;
            }
            if (value.contains("://") || value.startsWith("/") || value.startsWith("./")
                    || value.startsWith("~")) {
                return false;   // URL 与路径
            }
            if (NUMBER_LIKE.matcher(value).matches() || BOOLEAN_LIKE.matcher(value).matches()) {
                return false;   // 数字、时长、布尔
            }
            if (value.contains(" ") || value.startsWith("<") || value.startsWith("{{")
                    || value.startsWith("$")) {
                return false;   // 自然语言、模板、变量引用
            }
            // 形如 com.example.FooService 的值是类名或全限定名
            return !QUALIFIED_NAME.matcher(value).matches();
        }
    }

    // ==================================================================
    // 2. 提交了私钥
    // ==================================================================
    static final class PrivateKeyMaterialRule extends AbstractTextRule {

        private static final Pattern BASE64_LINE = Pattern.compile("^[A-Za-z0-9+/]{32,}={0,2}$");
        private static final Pattern BLOCK_END = Pattern.compile("-----END [A-Z ]*PRIVATE KEY-----");

        /**
         * PEM 私钥头
         * <p>
         * 用拼接而不是直接写字面量：本仓库会被本系统自己扫描，源码里出现完整的
         * PEM 头会让这条规则命中自己写的规则文件。
         */
        private static final Pattern PRIVATE_KEY_HEAD = Pattern.compile(
                "-----BEGIN " + "(RSA |DSA |EC |OPENSSH |PGP |ENCRYPTED )?" + "PRIVATE" + " KEY-----");

        PrivateKeyMaterialRule() {
            super("SEC.PRIVATE_KEY_MATERIAL", "提交了私钥", IssueType.SECURITY,
                    Severity.BLOCKER, 0.9,
                    "仓库中包含 PEM 私钥内容，任何拿到代码的人都能冒用该密钥，必须立即吊销并轮换",
                    Language.scannable());   // PEM 块可能出现在任何文本文件里
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> lines = lines(ctx);
            for (int index = 0; index < lines.size(); index++) {
                if (!PRIVATE_KEY_HEAD.matcher(lines.get(index)).find()) {
                    continue;
                }
                // 只有头部不算数：文档里贴的示例、被替换成省略号的占位都会留下头部。
                // 必须在其后找到真实密钥正文（一段长 base64）才开始报
                if (!hasKeyBody(lines, index)) {
                    continue;
                }
                return List.of(issueAtLine(ctx, index + 1,
                        "文件中包含 PEM 私钥内容",
                        "立刻吊销并轮换该密钥，改从密钥管理服务读取；同时清理 Git 历史中的残留"));
            }
            return List.of();
        }

        /** 头部之后、结束标记之前是否存在一段像样的 base64 正文 */
        private static boolean hasKeyBody(List<String> lines, int headIndex) {
            int limit = Math.min(lines.size(), headIndex + 200);
            for (int i = headIndex + 1; i < limit; i++) {
                String line = lines.get(i).trim();
                if (BLOCK_END.matcher(line).find()) {
                    return false;   // 结束得太早，正文段是空的，多半是占位
                }
                if (BASE64_LINE.matcher(line).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    // ==================================================================
    // 3. 明文 http 地址
    // ==================================================================
    static final class InsecureHttpEndpointRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = EnumSet.of(
                Language.YAML, Language.PROPERTIES, Language.TOML);

        private static final Pattern KEY_VALUE = Pattern.compile(
                "^\\s*-?\\s*([A-Za-z0-9_.\\-\\[\\]]+)\\s*[:=]\\s*(\\S.*)$");

        /** 值里出现明文 http 才值得看 */
        private static final Pattern HTTP_URL = Pattern.compile("http://([^\\s\"'#,)}]+)", Pattern.CASE_INSENSITIVE);

        /** 键名必须像「地址」，否则 http:// 可能只是某个描述性字段的内容 */
        private static final Pattern ENDPOINT_KEY = Pattern.compile(
                "(?i).*(url|uri|endpoint|host|address|server|gateway|proxy|api|registry|webhook|callback).*");

        /** 本机地址不构成明文传输风险 */
        private static final Pattern LOCAL_HOST = Pattern.compile(
                "(?i)^(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|::1|host\\.docker\\.internal)$");

        /** 内网网段 */
        private static final Pattern PRIVATE_HOST = Pattern.compile(
                "^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.)");

        /**
         * 示例域名与保留顶级域
         * <p>
         * 必须**整段锚定**：{@code .*\.internal} 这种写法会把
         * {@code registry.internal-corp.cn} 也一并豁免掉 —— 它只是恰好包含
         * 「.internal」这串字符，和保留域 {@code .internal} 毫无关系。
         */
        private static final Pattern PLACEHOLDER_HOST = Pattern.compile(
                "(?i)^(example\\.(com|org|net|edu)|[^.]+\\.example\\.(com|org|net|edu)"
                        + "|[^.]+\\.(local|internal|test|invalid|localhost))$");

        InsecureHttpEndpointRule() {
            super("SEC.INSECURE_HTTP_ENDPOINT", "明文 http 地址", IssueType.SECURITY,
                    Severity.MINOR, 0.4,
                    "配置中使用了明文 http 地址，流量可被窃听或篡改，应改为 https（本机与内网地址已豁免）",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            List<String> lines = lines(ctx);
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = stripInlineComment(ctx, lines.get(index));
                if (isBlankOrComment(ctx, line)) {
                    continue;
                }
                Matcher keyValue = KEY_VALUE.matcher(line);
                if (!keyValue.matches() || !ENDPOINT_KEY.matcher(keyValue.group(1)).matches()) {
                    continue;
                }
                Matcher url = HTTP_URL.matcher(keyValue.group(2));
                if (!url.find() || isExemptHost(url.group(1))) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "配置项 " + keyValue.group(1) + " 使用了明文 http 地址 " + url.group(1),
                        "改用 https；若服务本身不支持 TLS，应在内网网关处终止 TLS 而不是走明文"));
            }
            return issues;
        }

        /** 本机、内网、示例域名三类都豁免 */
        private static boolean isExemptHost(String urlTail) {
            String host = hostOf(urlTail);
            return LOCAL_HOST.matcher(host).matches()
                    || PRIVATE_HOST.matcher(host).find()
                    || PLACEHOLDER_HOST.matcher(host).matches();
        }

        /** 从「主机[:端口][/路径]」中取出纯主机名，供整段匹配使用 */
        private static String hostOf(String urlTail) {
            int cut = urlTail.length();
            for (int i = 0; i < urlTail.length(); i++) {
                char c = urlTail.charAt(i);
                if (c == '/' || c == '?' || c == '#' || c == ':') {
                    cut = i;
                    break;
                }
            }
            return urlTail.substring(0, cut);
        }
    }

    // ==================================================================
    // 4. 危险 shell 命令
    // ==================================================================
    static final class ShellDangerousCommandRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = EnumSet.of(
                Language.SHELL, Language.DOCKERFILE, Language.MAKEFILE, Language.YAML);

        /** CI 配置文件名：只有这些 YAML 才值得按 shell 规则检查 */
        private static final Pattern CI_FILE = Pattern.compile(
                "(?i).*(\\.github/workflows/.*\\.ya?ml|\\.gitlab-ci\\.ya?ml|azure-pipelines\\.ya?ml"
                        + "|Jenkinsfile|.*\\.jenkinsfile)$");

        /** 下载即执行：把远端内容直接喂给解释器 */
        private static final Pattern PIPE_TO_SHELL = Pattern.compile(
                "\\b(curl|wget)\\b[^|\\n]*\\|[^|\\n]*\\b(sh|bash|zsh|dash|python[23]?|perl)\\b");

        /** 解码后执行，等同上面的变体 */
        private static final Pattern PIPE_DECODED = Pattern.compile(
                "\\b(base64\\s+(-d|--decode)|openssl\\s+enc\\b)[^|\\n]*\\|[^|\\n]*\\b(sh|bash|zsh)\\b");

        /** 任何人对任何文件都有写权限 */
        private static final Pattern WORLD_WRITABLE = Pattern.compile(
                "\\bchmod\\s+(-[a-zA-Z]+\\s+)*777\\b");

        /** 删除根目录或家目录 */
        private static final Pattern DESTRUCTIVE_RM = Pattern.compile(
                "\\brm\\s+-[a-zA-Z]*[rR][a-zA-Z]*\\s+(/|/\\*|~|\\$HOME)\\s*(#.*)?$");

        /** fork 炸弹 */
        private static final Pattern FORK_BOMB = Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&");

        ShellDangerousCommandRule() {
            super("SEC.SHELL_DANGEROUS_COMMAND", "危险 shell 命令", IssueType.SECURITY,
                    Severity.MAJOR, 0.6,
                    "脚本中包含下载即执行、全局放开权限或破坏性删除等危险写法，会放大供应链与误操作风险",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            // YAML 只检查 CI 流水线：普通 application.yml 里出现 curl 并不值得按脚本口径审查
            if (ctx.language() == Language.YAML
                    && !CI_FILE.matcher(ctx.filePath() == null ? "" : ctx.filePath()).matches()) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            List<String> lines = lines(ctx);
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = lines.get(index);
                if (isBlankOrComment(ctx, line)) {
                    continue;
                }
                String hit = matchDangerous(line);
                if (hit == null) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "脚本中包含" + hit,
                        "避免下载即执行：改为校验签名后安装固定版本；权限最小化，删除操作前确认路径变量非空"));
            }
            return issues;
        }

        private static String matchDangerous(String line) {
            if (PIPE_TO_SHELL.matcher(line).find()) {
                return "「下载内容直接管道给 shell 执行」的写法";
            }
            if (PIPE_DECODED.matcher(line).find()) {
                return "「解码后直接执行」的写法";
            }
            if (FORK_BOMB.matcher(line).find()) {
                return "fork 炸弹";
            }
            if (WORLD_WRITABLE.matcher(line).find()) {
                return "chmod 777 全局放开文件权限";
            }
            if (DESTRUCTIVE_RM.matcher(line).find()) {
                return "对根目录或家目录的递归删除";
            }
            return null;
        }
    }

    // ==================================================================
    // 5. 容器以 root 运行
    // ==================================================================
    static final class DockerRunAsRootRule extends AbstractTextRule {

        /** 基础镜像本身就以非 root 运行的常见标记 */
        private static final Pattern NON_ROOT_IMAGE = Pattern.compile(
                "(?i).*(distroless|nonroot|unprivileged|bitnami|alpine.*nonroot|:rootless).*");

        private static final Pattern FROM = Pattern.compile("(?i)^\\s*FROM\\s+(.+)$");
        private static final Pattern USER = Pattern.compile("(?i)^\\s*USER\\s+(.+)$");

        DockerRunAsRootRule() {
            super("SEC.DOCKER_RUN_AS_ROOT", "容器以 root 运行", IssueType.SECURITY,
                    Severity.MINOR, 0.5,
                    "Dockerfile 的构建阶段没有切换到非 root 用户，容器被攻破后攻击者将直接获得 root 权限",
                    Set.of(Language.DOCKERFILE));
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> lines = lines(ctx);
            // 只看最后一个构建阶段：多阶段构建中前面几个阶段只是编译环境，不进入最终镜像
            int lastFrom = -1;
            for (int index = 0; index < lines.size(); index++) {
                if (FROM.matcher(joinContinuations(lines, index).line).matches()) {
                    lastFrom = index;
                }
            }
            if (lastFrom < 0) {
                return List.of();
            }

            boolean switchedUser = false;
            boolean hasInstruction = false;
            for (int index = lastFrom + 1; index < lines.size(); index++) {
                String text = joinContinuations(lines, index).line;
                if (isBlankOrComment(ctx, text)) {
                    continue;
                }
                hasInstruction = true;
                Matcher user = USER.matcher(text);
                if (user.matches() && isNonRootUser(user.group(1))) {
                    switchedUser = true;
                }
            }
            // 基础镜像自带非 root 用户、写明了 USER、或这个阶段空无一物，都不报
            if (switchedUser || !hasInstruction
                    || NON_ROOT_IMAGE.matcher(lines.get(lastFrom)).matches()) {
                return List.of();
            }
            return List.of(issueAtLine(ctx, lastFrom + 1,
                    "最后一个构建阶段始终以 root 运行（没有任何非 root 的 USER 指令）",
                    "加一条 USER 指令切换到非 root 用户，或改用 distroless 等自带非 root 用户的镜像"));
        }

        /** USER 指令的目标不是 root */
        private static boolean isNonRootUser(String argument) {
            String value = argument.trim().split("\\s+")[0];
            if (value.isEmpty() || value.startsWith("$")) {
                return true;   // 变量形式无法静态判定，按已切换处理，避免误报
            }
            return !value.equals("0") && !value.equalsIgnoreCase("root")
                    && !value.startsWith("0:");
        }

        /** 把反斜杠续行拼成一条逻辑行 */
        private static LogicalLine joinContinuations(List<String> lines, int index) {
            String first = lines.get(index);
            if (!first.stripTrailing().endsWith("\\")) {
                return new LogicalLine(first);
            }
            StringBuilder joined = new StringBuilder(first.substring(0, first.stripTrailing().length() - 1));
            int next = index + 1;
            while (next < lines.size()) {
                String part = lines.get(next);
                boolean continued = part.stripTrailing().endsWith("\\");
                joined.append(' ').append(continued
                        ? part.substring(0, part.stripTrailing().length() - 1) : part);
                next++;
                if (!continued) {
                    break;
                }
            }
            return new LogicalLine(joined.toString());
        }

        private record LogicalLine(String line) {}
    }
}
