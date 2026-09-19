package com.cq.rule.security;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.parser.AstScopeUtils;
import com.cq.rule.AbstractRule;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 安全维度规则（10 条）
 * <p>
 * 无符号求解器，因此这一组以「字面量参数 + 名称模式」为主要判据，尽量只匹配
 * 语法上无歧义的写法。置信度普遍低于 Bug 组 —— 安全问题的最终判定依赖数据流，
 * 语法层只能给出「疑似」，这也是双引擎设计里 Code Agent 需要介入的地方。
 */
public final class SecurityRules {

    private SecurityRules() {}

    /** 全部安全规则 */
    public static List<Rule> all() {
        return List.of(
                new SqlInjectionRule(),
                new HardcodedCredentialRule(),
                new CommandInjectionRule(),
                new WeakHashRule(),
                new InsecureRandomRule(),
                new XxeRule(),
                new InsecureDeserializationRule(),
                new TrustAllCertRule(),
                new SensitiveLogRule(),
                new EcbModeRule());
    }

    /** 疑似凭证的变量名 */
    private static final Pattern CREDENTIAL_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|credential|private[_-]?key).*");

    /** 明文凭证的占位值，不应报告 */
    private static final List<String> PLACEHOLDER_VALUES = List.of(
            "", "test", "changeme", "todo", "xxx", "your_password", "password",
            "example", "placeholder", "123456");

    /** 日志方法名 */
    private static final List<String> LOG_METHODS = List.of(
            "trace", "debug", "info", "warn", "error", "fatal");

    /** 数据库操作方法名（小写比较） */
    private static final List<String> SQL_METHODS = List.of(
            "executequery", "executeupdate", "executelargeupdate", "execute", "preparestatement",
            "preparecall", "createquery", "createnativequery", "createcriteria",
            "query", "queryforobject", "queryforlist", "update", "selectone", "selectlist",
            "selectcount", "findbyid", "save", "insert", "delete");

    // ------------------------------------------------------------------
    // 1. SQL 注入
    // ------------------------------------------------------------------
    static final class SqlInjectionRule extends AbstractRule {

        SqlInjectionRule() {
            super("SEC.SQL_INJECTION", "SQL 拼接注入风险", IssueType.SECURITY,
                    Severity.CRITICAL, 0.6, "SQL 语句由字符串拼接构造，存在注入风险，应使用预编译参数");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!SQL_METHODS.contains(call.getNameAsString().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (Expression argument : call.getArguments()) {
                    if (!(argument instanceof BinaryExpr binary)
                            || binary.getOperator() != BinaryExpr.Operator.PLUS) {
                        continue;
                    }
                    // 拼进去的是 int/long/boolean 时不构成注入（无法携带引号），
                    // 这条判断是整组安全规则里降噪效果最大的一条
                    if (isDefinitelyNonString(binary, ctx)) {
                        continue;
                    }
                    issues.add(issue(ctx, call,
                            "SQL 语句通过字符串拼接构造，存在注入风险",
                            "改用预编译参数：PreparedStatement 的 ? 占位符，或 ORM 的参数绑定"));
                    break;
                }
            }
            return issues;
        }

        /**
         * 拼接是否安全：所有**动态**操作数都能确定不是字符串
         * <p>
         * 判定要点是把「拼接结果是不是字符串」和「拼进去的值能不能携带引号」分开：
         * 字符串字面量是常量片段，本身不构成注入；风险只来自变量。
         * 因此 {@code "SELECT ... WHERE id=" + id} 中 {@code id} 是 int 时是安全的，
         * 而 {@code "... name='" + name + "'"} 中 {@code name} 是 String 时不安全。
         * <p>
         * 类型无法判定的操作数按有风险处理，宁可多报也不漏掉真正的拼接注入。
         */
        private static boolean isDefinitelyNonString(Expression expression, RuleContext ctx) {
            if (expression instanceof StringLiteralExpr) {
                return true;   // 常量片段，无法携带外部输入
            }
            if (expression instanceof BinaryExpr nested) {
                return isDefinitelyNonString(nested.getLeft(), ctx)
                        && isDefinitelyNonString(nested.getRight(), ctx);
            }
            String type = ctx.types().typeOf(expression);
            return type != null && !type.equals("String");
        }
    }

    // ------------------------------------------------------------------
    // 2. 硬编码凭证
    // ------------------------------------------------------------------
    static final class HardcodedCredentialRule extends AbstractRule {

        HardcodedCredentialRule() {
            super("SEC.HARDCODED_CREDENTIAL", "硬编码凭证", IssueType.SECURITY,
                    Severity.BLOCKER, 0.75, "口令、密钥等凭证直接写在源码中，会随代码库泄露");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (VariableDeclarator variable : ctx.findAll(VariableDeclarator.class)) {
                String name = variable.getNameAsString();
                if (!CREDENTIAL_NAME.matcher(name).matches()) {
                    continue;
                }
                String literal = stringLiteralOf(variable.getInitializer().orElse(null));
                if (literal == null || isPlaceholder(literal)) {
                    continue;
                }
                issues.add(issue(ctx, variable,
                        "变量 " + name + " 直接硬编码了字符串凭证",
                        "改为从环境变量或配置中心读取，并轮换已泄露的凭证"));
            }
            // setPassword("...") 这类 setter 调用
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                String methodName = call.getNameAsString();
                if (!methodName.toLowerCase(Locale.ROOT).startsWith("set")
                        || !CREDENTIAL_NAME.matcher(methodName).matches()) {
                    continue;
                }
                if (call.getArguments().size() != 1) {
                    continue;
                }
                String literal = stringLiteralOf(call.getArgument(0));
                if (literal == null || isPlaceholder(literal)) {
                    continue;
                }
                issues.add(issue(ctx, call,
                        methodName + " 调用中直接写入了字符串凭证",
                        "改为从环境变量或配置中心读取"));
            }
            return issues;
        }

        private static String stringLiteralOf(Expression expression) {
            if (expression instanceof StringLiteralExpr literal) {
                String value = literal.getValue();
                return value.length() >= 4 ? value : null;   // 过短的常量多为占位
            }
            return null;
        }

        private static boolean isPlaceholder(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (PLACEHOLDER_VALUES.contains(normalized)) {
                return true;
            }
            // ${...} 或 #{} 形式的占位符
            return normalized.startsWith("${") || normalized.startsWith("#{");
        }
    }

    // ------------------------------------------------------------------
    // 3. 命令注入
    // ------------------------------------------------------------------
    static final class CommandInjectionRule extends AbstractRule {

        CommandInjectionRule() {
            super("SEC.COMMAND_INJECTION", "命令注入风险", IssueType.SECURITY,
                    Severity.CRITICAL, 0.75, "执行外部命令时使用了非字面量参数，可能被注入");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("exec")) {
                    continue;
                }
                boolean fromRuntime = call.getScope()
                        .map(scope -> scope.toString().contains("getRuntime"))
                        .orElse(false);
                if (!fromRuntime) {
                    continue;
                }
                if (hasDynamicArgument(call.getArguments())) {
                    issues.add(issue(ctx, call,
                            "Runtime.exec 的参数不是字符串字面量，存在命令注入风险",
                            "改用 ProcessBuilder 并校验参数白名单，避免拼接外部输入"));
                }
            }
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (!creation.getTypeAsString().equals("ProcessBuilder")) {
                    continue;
                }
                List<Expression> arguments = new ArrayList<>();
                creation.getArguments().forEach(arguments::add);
                if (hasDynamicArgument(arguments)) {
                    issues.add(issue(ctx, creation,
                            "ProcessBuilder 的参数不是字符串字面量，存在命令注入风险",
                            "校验参数白名单，避免拼接外部输入"));
                }
            }
            return issues;
        }

        /** 参数中是否存在非字面量、非常量名的动态值 */
        private static boolean hasDynamicArgument(List<Expression> arguments) {
            for (Expression argument : arguments) {
                if (argument instanceof StringLiteralExpr) {
                    continue;
                }
                // 全大写标识符视为常量，是安全的
                if (argument instanceof NameExpr nameExpr
                        && nameExpr.getNameAsString().matches("[A-Z][A-Z0-9_]*")) {
                    continue;
                }
                return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 4. 弱哈希
    // ------------------------------------------------------------------
    static final class WeakHashRule extends AbstractRule {

        WeakHashRule() {
            super("SEC.WEAK_HASH", "使用弱哈希算法", IssueType.SECURITY,
                    Severity.MAJOR, 0.85, "MD5/SHA-1 已不具备抗碰撞能力，不应用于安全场景");
        }

        private static final List<String> WEAK = List.of("md2", "md4", "md5", "sha", "sha-1", "sha1");

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                String methodName = call.getNameAsString();
                boolean getInstance = methodName.equals("getInstance")
                        && call.getScope().map(scope -> typeNameIs(scope.toString(), "MessageDigest")).orElse(false);
                boolean digestUtils = methodName.toLowerCase(Locale.ROOT).startsWith("md5")
                        || methodName.equals("sha1Hex");
                if (!getInstance && !digestUtils) {
                    continue;
                }
                if (getInstance) {
                    String algorithm = firstStringLiteral(call);
                    if (algorithm == null || !WEAK.contains(algorithm.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }
                // 变量名暗示用途与安全无关（校验和/去重）时降级
                boolean nonSecurityUse = call.getParentNode()
                        .flatMap(parent -> parent.findFirst(VariableDeclarator.class))
                        .map(declarator -> declarator.getNameAsString().toLowerCase(Locale.ROOT))
                        .map(name -> name.contains("checksum") || name.contains("etag")
                                || name.contains("dedup") || name.contains("fingerprint"))
                        .orElse(false);
                issues.add(issue(ctx, call,
                        "使用弱哈希算法 MD5/SHA-1",
                        "安全场景改用 SHA-256 及以上；口令存储应使用 bcrypt/argon2",
                        nonSecurityUse ? Severity.MINOR : null,
                        nonSecurityUse ? 0.5 : null));
            }
            return issues;
        }

        private static String firstStringLiteral(MethodCallExpr call) {
            for (Expression argument : call.getArguments()) {
                if (argument instanceof StringLiteralExpr literal) {
                    return literal.getValue();
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 5. 不安全的随机数
    // ------------------------------------------------------------------
    static final class InsecureRandomRule extends AbstractRule {

        InsecureRandomRule() {
            super("SEC.INSECURE_RANDOM", "安全场景使用伪随机数", IssueType.SECURITY,
                    Severity.MINOR, 0.5,
                    "java.util.Random 可被预测，不应生成令牌、盐值等安全敏感数据");
        }

        private static final Pattern SENSITIVE = Pattern.compile(
                "(?i).*(token|salt|nonce|otp|session|apikey|api_key|password|secret).*");

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (!typeNameIs(creation.getTypeAsString(), "Random", "ThreadLocalRandom")) {
                    continue;
                }
                if (!assignedToSensitiveName(creation)) {
                    continue;
                }
                issues.add(issue(ctx, creation,
                        "使用 java.util.Random 生成安全敏感数据，其序列可被预测",
                        "改用 java.security.SecureRandom"));
            }
            return issues;
        }

        /** 仅当赋给名字暗示安全用途的变量时才报告 —— 「安全用途」在语法层不可判定 */
        private static boolean assignedToSensitiveName(Node node) {
            return node.findAncestor(VariableDeclarator.class)
                    .map(declarator -> SENSITIVE.matcher(declarator.getNameAsString()).matches())
                    .orElse(false);
        }
    }

    // ------------------------------------------------------------------
    // 6. XXE
    // ------------------------------------------------------------------
    static final class XxeRule extends AbstractRule {

        XxeRule() {
            super("SEC.XXE", "XML 解析未禁用外部实体", IssueType.SECURITY,
                    Severity.CRITICAL, 0.7, "未加固的 XML 解析器存在 XXE 漏洞，可读取本地文件或发起 SSRF");
        }

        private static final List<String> FACTORY_TYPES = List.of(
                "DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory",
                "SAXReader", "SAXBuilder", "TransformerFactory");

        private static final List<String> HARDENING_KEYWORDS = List.of(
                "disallow-doctype-decl", "external-general-entities", "external-parameter-entities",
                "access_external_dtd", "access_external_stylesheet", "expandentityreferences",
                "setExpandEntityReferences", "setFeature", "setAttribute");

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (!typeNameIs(creation.getTypeAsString(), FACTORY_TYPES.toArray(new String[0]))) {
                    continue;
                }
                CallableDeclaration<?> owner = AstScopeUtils.enclosingCallable(creation).orElse(null);
                if (owner != null && isHardened(owner)) {
                    continue;
                }
                issues.add(issue(ctx, creation,
                        "创建 " + creation.getTypeAsString() + " 后未禁用外部实体，存在 XXE 风险",
                        "设置 setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true)"));
            }
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("newInstance")) {
                    continue;
                }
                boolean knownFactory = call.getScope()
                        .map(scope -> typeNameIs(scope.toString(), FACTORY_TYPES.toArray(new String[0])))
                        .orElse(false);
                if (!knownFactory) {
                    continue;
                }
                CallableDeclaration<?> owner = AstScopeUtils.enclosingCallable(call).orElse(null);
                if (owner != null && isHardened(owner)) {
                    continue;
                }
                issues.add(issue(ctx, call,
                        call.getScope().map(Object::toString).orElse("XML 工厂")
                                + ".newInstance() 后未禁用外部实体，存在 XXE 风险",
                        "禁用 DOCTYPE 声明与外部实体解析"));
            }
            return issues;
        }

        /** 同一方法内是否出现过加固调用 */
        private static boolean isHardened(CallableDeclaration<?> owner) {
            for (MethodCallExpr call : owner.findAll(MethodCallExpr.class)) {
                for (Expression argument : call.getArguments()) {
                    String text = argument.toString();
                    for (String keyword : HARDENING_KEYWORDS) {
                        if (text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 7. 不安全的反序列化
    // ------------------------------------------------------------------
    static final class InsecureDeserializationRule extends AbstractRule {

        InsecureDeserializationRule() {
            super("SEC.INSECURE_DESERIALIZATION", "不安全的反序列化", IssueType.SECURITY,
                    Severity.CRITICAL, 0.7, "对不可信数据做原生反序列化可导致远程代码执行");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                String type = creation.getTypeAsString();
                if (typeNameIs(type, "ObjectInputStream", "XMLDecoder")) {
                    issues.add(issue(ctx, creation,
                            "使用 " + type + " 反序列化数据，若来源不可信可导致远程代码执行",
                            "改用 JSON 等安全格式，或使用带类型白名单的 ObjectInputFilter"));
                }
            }
            // readObject 的调用方类型需可判定，否则会把领域对象自身的 readObject 全报出来
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("readObject")) {
                    continue;
                }
                Expression scope = call.getScope().orElse(null);
                if (!ctx.types().isType(scope, "ObjectInputStream")) {
                    continue;
                }
                issues.add(issue(ctx, call,
                        "ObjectInputStream.readObject 反序列化不可信数据",
                        "使用 ObjectInputFilter 设置类型白名单"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 8. 信任所有证书
    // ------------------------------------------------------------------
    static final class TrustAllCertRule extends AbstractRule {

        TrustAllCertRule() {
            super("SEC.TRUST_ALL_CERT", "信任所有 TLS 证书", IssueType.SECURITY,
                    Severity.BLOCKER, 0.9, "信任所有证书使 TLS 失去防中间人攻击的能力");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (creation.getAnonymousClassBody().isEmpty()) {
                    continue;
                }
                String type = creation.getTypeAsString();
                if (typeNameIs(type, "X509TrustManager")) {
                    boolean allEmpty = creation.getAnonymousClassBody().get().stream()
                            .filter(body -> body instanceof com.github.javaparser.ast.body.MethodDeclaration)
                            .map(body -> (com.github.javaparser.ast.body.MethodDeclaration) body)
                            .filter(method -> method.getNameAsString().startsWith("check"))
                            .allMatch(method -> method.getBody()
                                    .map(block -> block.getStatements().isEmpty())
                                    .orElse(false));
                    if (allEmpty) {
                        issues.add(issue(ctx, creation,
                                "自定义 X509TrustManager 的校验方法为空，等价于信任所有证书",
                                "删除该实现，使用系统默认的信任链校验"));
                    }
                }
                if (typeNameIs(type, "HostnameVerifier") && alwaysReturnsTrue(creation)) {
                    issues.add(issue(ctx, creation,
                            "HostnameVerifier 恒返回 true，跳过主机名校验",
                            "删除该实现，使用默认的主机名校验"));
                }
            }
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("getInstance")
                        || !call.getScope().map(scope -> typeNameIs(scope.toString(), "SSLContext")).orElse(false)) {
                    continue;
                }
                for (Expression argument : call.getArguments()) {
                    if (argument instanceof StringLiteralExpr literal) {
                        String protocol = literal.getValue();
                        if (protocol.equals("SSL") || protocol.equals("TLSv1") || protocol.equals("TLSv1.1")) {
                            issues.add(issue(ctx, call,
                                    "使用已不安全的 TLS 协议版本 " + protocol,
                                    "改用 SSLContext.getInstance(\"TLSv1.2\") 或更高版本"));
                        }
                    }
                }
            }
            return issues;
        }

        private static boolean alwaysReturnsTrue(ObjectCreationExpr creation) {
            return creation.getAnonymousClassBody().get().stream()
                    .filter(body -> body instanceof com.github.javaparser.ast.body.MethodDeclaration)
                    .map(body -> (com.github.javaparser.ast.body.MethodDeclaration) body)
                    .flatMap(method -> method.getBody().stream())
                    .flatMap(block -> block.getStatements().stream())
                    .filter(statement -> statement instanceof ReturnStmt)
                    .map(statement -> ((ReturnStmt) statement).getExpression().orElse(null))
                    .anyMatch(expression -> expression instanceof BooleanLiteralExpr
                            && ((BooleanLiteralExpr) expression).getValue());
        }
    }

    // ------------------------------------------------------------------
    // 9. 敏感信息进日志
    // ------------------------------------------------------------------
    static final class SensitiveLogRule extends AbstractRule {

        SensitiveLogRule() {
            super("SEC.SENSITIVE_LOG", "日志打印敏感信息", IssueType.SECURITY,
                    Severity.MAJOR, 0.65, "把口令、令牌等写入日志会造成凭证泄露");
        }

        private static final Pattern SENSITIVE = Pattern.compile(
                "(?i)(password|passwd|pwd|secret|token|credential|api[_-]?key|access[_-]?key|idcard|ssn)");

        /** 名字里含这些后缀的多为计数/名称，并非敏感值本身 */
        private static final Pattern SAFE_SUFFIX = Pattern.compile("(?i).*(count|length|size|name|type|id)$");

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!LOG_METHODS.contains(call.getNameAsString())) {
                    continue;
                }
                boolean loggerScope = call.getScope()
                        .map(scope -> scope.toString().toLowerCase(Locale.ROOT).contains("log"))
                        .orElse(false);
                if (!loggerScope) {
                    continue;
                }
                for (Expression argument : call.getArguments()) {
                    if (!(argument instanceof NameExpr || argument instanceof com.github.javaparser.ast.expr.FieldAccessExpr)) {
                        continue;
                    }
                    String name = argument instanceof NameExpr nameExpr
                            ? nameExpr.getNameAsString()
                            : ((com.github.javaparser.ast.expr.FieldAccessExpr) argument).getNameAsString();
                    if (!SENSITIVE.matcher(name).find() || SAFE_SUFFIX.matcher(name).matches()) {
                        continue;
                    }
                    issues.add(issue(ctx, call,
                            "日志中直接打印了疑似敏感字段 " + name,
                            "脱敏后再记录，或仅记录是否存在"));
                    break;
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 10. ECB 分组模式
    // ------------------------------------------------------------------
    static final class EcbModeRule extends AbstractRule {

        EcbModeRule() {
            super("SEC.ECB_MODE", "使用 ECB 分组模式", IssueType.SECURITY,
                    Severity.MAJOR, 0.8, "ECB 模式对相同明文块产生相同密文块，会泄露明文结构");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("getInstance")
                        || !call.getScope().map(scope -> typeNameIs(scope.toString(), "Cipher")).orElse(false)) {
                    continue;
                }
                for (Expression argument : call.getArguments()) {
                    if (!(argument instanceof StringLiteralExpr literal)) {
                        continue;
                    }
                    String transformation = literal.getValue();
                    // "AES" 不带模式时 JDK 默认就是 ECB
                    if (transformation.equals("AES") || transformation.contains("/ECB/")) {
                        issues.add(issue(ctx, call,
                                "Cipher 使用 ECB 分组模式（" + transformation + "），会泄露明文结构",
                                "改用带 IV 的 GCM 模式，如 Cipher.getInstance(\"AES/GCM/NoPadding\")"));
                    }
                }
            }
            return issues;
        }
    }
}
