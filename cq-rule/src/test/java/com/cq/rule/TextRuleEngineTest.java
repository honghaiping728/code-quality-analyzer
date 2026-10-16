package com.cq.rule;

import com.cq.common.Issue;
import com.cq.parser.ParsedFile;
import com.cq.parser.SourceParserRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本规则测试（面向非 Java 文件）
 * <p>
 * 与 {@link RuleEngineTest} 同一套约定：每条规则都配「正例命中 + 负例不误报」，
 * <b>负例才是重点</b>。文本规则靠正则与行形态判断，没有类型信息兜底，
 * 误报风险天然高于 AST 规则 —— 一条把 XML 命名空间当成明文 http 地址的规则，
 * 会让整个安全维度被使用者直接关掉。
 * <p>
 * 这里还额外覆盖「语言隔离」：Java 规则不得在 YAML 上命中，文本规则也不该
 * 越界跑到它没声明的语言上。
 */
class TextRuleEngineTest {

    private static final SourceParserRegistry PARSER = SourceParserRegistry.defaults();
    private static final RuleEngine ENGINE = new RuleEngine();

    // ==================== 工具 ====================

    /** 按文件路径解析源码并跑全部规则，返回命中的规则 ID 集合 */
    private static Set<String> hits(String path, String source) {
        ParsedFile file = PARSER.parse(source, path);
        return ENGINE.analyze(file).stream()
                .map(Issue::getRuleId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static void assertFires(String ruleId, String path, String source) {
        Set<String> found = hits(path, source);
        assertTrue(found.contains(ruleId), "期望 " + ruleId + " 在 " + path + " 上命中，实际命中: " + found);
    }

    private static void assertSilent(String ruleId, String path, String source) {
        Set<String> found = hits(path, source);
        assertFalse(found.contains(ruleId),
                "期望 " + ruleId + " 在 " + path + " 上不命中，但它误报了。全部命中: " + found);
    }

    // ==================== SEC.CONFIG_PLAINTEXT_SECRET ====================

    @Test
    @DisplayName("SEC.CONFIG_PLAINTEXT_SECRET 命中 YAML 里的明文口令")
    void plaintextSecretInYamlFires() {
        assertFires("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", """
                spring:
                  datasource:
                    username: app
                    password: Pr0ductionP@ss
                """);
    }

    @Test
    @DisplayName("SEC.CONFIG_PLAINTEXT_SECRET 命中 .env 与 properties")
    void plaintextSecretInEnvFires() {
        assertFires("SEC.CONFIG_PLAINTEXT_SECRET", ".env", "DB_PASSWORD=Pr0ductionP@ss\n");
        assertFires("SEC.CONFIG_PLAINTEXT_SECRET", "app.properties", "api.key=9f8e7d6c5b4a\n");
    }

    @Test
    @DisplayName("SEC.CONFIG_PLAINTEXT_SECRET 命中 Dockerfile 的 ENV 声明")
    void plaintextSecretInDockerfileFires() {
        assertFires("SEC.CONFIG_PLAINTEXT_SECRET", "Dockerfile",
                "FROM eclipse-temurin:21\nENV DB_PASSWORD=Pr0ductionP@ss\n");
    }

    @Test
    @DisplayName("占位符、示例值与掩码不得报")
    void placeholderSecretsMustNotFire() {
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", "password: ${DB_PASSWORD}\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", "password: changeme\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", "secret: '********'\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.properties", "password=\n");
    }

    @Test
    @DisplayName("值不是凭证形态时不得报：路径、超时、类名、数字")
    void nonSecretValuesMustNotFire() {
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml",
                "secret-key-location: /etc/certs/private.pem\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", "token-timeout: 30s\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml",
                "secret-encoder: org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", "credential-count: 1234\n");
    }

    @Test
    @DisplayName("注释掉的配置与示例文件不得报")
    void commentedAndExampleSecretsMustNotFire() {
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml",
                "# password: Pr0ductionP@ss\n");
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", ".env.example",
                "DB_PASSWORD=Pr0ductionP@ss\n");
    }

    // ==================== SEC.PRIVATE_KEY_MATERIAL ====================

    @Test
    @DisplayName("SEC.PRIVATE_KEY_MATERIAL 命中带正文的 PEM 私钥")
    void privateKeyFires() {
        String key = "-----BEGIN " + "RSA " + "PRIVATE" + " KEY-----\n"
                + "MIIEowIBAAKCAQEAx7Vn1sQ0mZ0dK3fQk9pL2cR8tYbN4vW6xH0jA5uE1gS2oD7f\n"
                + "-----END " + "RSA " + "PRIVATE" + " KEY-----\n";
        // 无扩展名的 OpenSSH 私钥与 .pem 都在识别范围内，否则它们会在扫描入口就被跳过
        assertFires("SEC.PRIVATE_KEY_MATERIAL", "deploy/id_rsa", key);
        assertFires("SEC.PRIVATE_KEY_MATERIAL", "certs/server.pem", key);
        assertFires("SEC.PRIVATE_KEY_MATERIAL", "application.yml", "key: |\n  " + key.replace("\n", "\n  "));
    }

    @Test
    @DisplayName("只有头部的示例与省略号占位不得报")
    void privateKeyPlaceholderMustNotFire() {
        String placeholder = "-----BEGIN " + "PRIVATE" + " KEY-----\n"
                + "...\n"
                + "-----END " + "PRIVATE" + " KEY-----\n";
        assertSilent("SEC.PRIVATE_KEY_MATERIAL", "docs/setup.md", placeholder);

        String headOnly = "文档里只贴了个头：-----BEGIN " + "PRIVATE" + " KEY-----\n";
        assertSilent("SEC.PRIVATE_KEY_MATERIAL", "README.md", headOnly);
    }

    // ==================== SEC.INSECURE_HTTP_ENDPOINT ====================

    @Test
    @DisplayName("SEC.INSECURE_HTTP_ENDPOINT 命中配置里的明文 http 地址")
    void insecureHttpFires() {
        assertFires("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "registry:\n  url: http://registry.internal-corp.cn/repo\n");
    }

    @Test
    @DisplayName("本机、内网与示例域名不得报")
    void localAndPlaceholderHostsMustNotFire() {
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "server:\n  url: http://localhost:8080/api\n");
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "server:\n  url: http://127.0.0.1:8080/api\n");
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "gateway:\n  url: http://10.20.30.40:9000\n");
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "gateway:\n  url: http://api.example.com\n");
    }

    @Test
    @DisplayName("键名不像地址或已是 https 时不得报")
    void nonEndpointKeysMustNotFire() {
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "description: 参见 http://wiki.internal-corp.cn 的说明\n");
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "application.yml",
                "server:\n  url: https://api.corp.cn\n");
    }

    @Test
    @DisplayName("XML 命名空间不在检查范围内（误报的最大来源）")
    void xmlNamespacesAreOutOfScope() {
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "pom.xml",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"></project>\n");
        assertSilent("SEC.INSECURE_HTTP_ENDPOINT", "mapper/UserMapper.xml",
                "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\""
                        + " \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n");
    }

    // ==================== SEC.SHELL_DANGEROUS_COMMAND ====================

    @Test
    @DisplayName("SEC.SHELL_DANGEROUS_COMMAND 命中下载即执行与全局放开权限")
    void dangerousShellFires() {
        assertFires("SEC.SHELL_DANGEROUS_COMMAND", "install.sh",
                "curl -fsSL https://get.example.org/install | sh\n");
        assertFires("SEC.SHELL_DANGEROUS_COMMAND", "install.sh", "chmod -R 777 /var/www\n");
        assertFires("SEC.SHELL_DANGEROUS_COMMAND", "deploy.sh", "rm -rf /\n");
        assertFires("SEC.SHELL_DANGEROUS_COMMAND", "Dockerfile",
                "RUN curl -fsSL https://x.example.org/a | bash\n");
    }

    @Test
    @DisplayName("注释里的危险命令与正常权限不得报")
    void safeShellMustNotFire() {
        assertSilent("SEC.SHELL_DANGEROUS_COMMAND", "install.sh",
                "# curl -fsSL https://x.example.org/a | sh\n");
        assertSilent("SEC.SHELL_DANGEROUS_COMMAND", "install.sh", "chmod 755 deploy.sh\n");
        assertSilent("SEC.SHELL_DANGEROUS_COMMAND", "install.sh", "rm -rf ./build\n");
        assertSilent("SEC.SHELL_DANGEROUS_COMMAND", "install.sh", "curl -O https://x.example.org/a.tar.gz\n");
    }

    @Test
    @DisplayName("普通业务 YAML 不按脚本口径检查，CI 流水线才检查")
    void onlyCiYamlIsCheckedAsScript() {
        String pipeline = "jobs:\n  build:\n    steps:\n      - run: curl -fsSL https://x.example.org/i | sh\n";
        assertSilent("SEC.SHELL_DANGEROUS_COMMAND", "application.yml", pipeline);
        assertFires("SEC.SHELL_DANGEROUS_COMMAND", ".github/workflows/ci.yml", pipeline);
    }

    // ==================== SEC.DOCKER_RUN_AS_ROOT ====================

    @Test
    @DisplayName("SEC.DOCKER_RUN_AS_ROOT 命中没有 USER 指令的镜像构建")
    void dockerRootFires() {
        assertFires("SEC.DOCKER_RUN_AS_ROOT", "Dockerfile", """
                FROM eclipse-temurin:21-jre
                WORKDIR /app
                COPY target/app.jar app.jar
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """);
    }

    @Test
    @DisplayName("切换了非 root 用户或基础镜像自带非 root 时不得报")
    void dockerNonRootMustNotFire() {
        assertSilent("SEC.DOCKER_RUN_AS_ROOT", "Dockerfile", """
                FROM eclipse-temurin:21-jre
                COPY target/app.jar app.jar
                USER 1001
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """);
        assertSilent("SEC.DOCKER_RUN_AS_ROOT", "Dockerfile", """
                FROM gcr.io/distroless/java21-debian12:nonroot
                COPY target/app.jar app.jar
                """);
    }

    @Test
    @DisplayName("多阶段构建只看最后一个阶段")
    void dockerMultiStageChecksLastStageOnly() {
        assertSilent("SEC.DOCKER_RUN_AS_ROOT", "Dockerfile", """
                FROM maven:3.9 AS build
                RUN mvn package
                FROM eclipse-temurin:21-jre
                COPY --from=build /app/target/app.jar app.jar
                USER app
                """);
    }

    // ==================== BUG.CONFLICT_MARKER ====================

    @Test
    @DisplayName("BUG.CONFLICT_MARKER 命中遗留的冲突标记（含 Java 文件）")
    void conflictMarkerFires() {
        String conflicted = "public class A {\n"
                + "<<<<<<< HEAD\n    int x = 1;\n=======\n    int x = 2;\n>>>>>>> feature\n}\n";
        assertFires("BUG.CONFLICT_MARKER", "src/A.java", conflicted);
        assertFires("BUG.CONFLICT_MARKER", "config/app.yml", "a: 1\n<<<<<<< HEAD\nb: 2\n>>>>>>> other\n");
    }

    @Test
    @DisplayName("整行等号（Markdown 标题下划线、分隔线）不得报")
    void dividerAloneMustNotFire() {
        assertSilent("BUG.CONFLICT_MARKER", "README.md", "标题\n=======\n\n正文\n");
        assertSilent("BUG.CONFLICT_MARKER", "deploy.sh", "echo start\n=======\necho end\n");
    }

    @Test
    @DisplayName("出现在字符串字面量中段的标记不构成冲突（未在行首）")
    void inlineMarkerMustNotFire() {
        assertSilent("BUG.CONFLICT_MARKER", "src/A.java",
                "class A { String s = \"<<<<<<< HEAD\"; }\n");
    }

    // ==================== BUG.DEBUG_STATEMENT ====================

    @Test
    @DisplayName("BUG.DEBUG_STATEMENT 命中前端与 Python 的调试输出")
    void debugStatementFires() {
        assertFires("BUG.DEBUG_STATEMENT", "src/api/index.js", "export function f(x) { console.log(x); }\n");
        assertFires("BUG.DEBUG_STATEMENT", "src/views/Home.vue", "<script>\nconsole.log('mounted')\n</script>\n");
        assertFires("BUG.DEBUG_STATEMENT", "scripts/run.py", "def f():\n    print(x)\n");
        assertFires("BUG.DEBUG_STATEMENT", "cmd/main.go", "func main() { fmt.Println(\"x\") }\n");
        assertFires("BUG.DEBUG_STATEMENT", "deploy.sh", "set -x\n");
    }

    @Test
    @DisplayName("注释掉的调试输出不得报")
    void commentedDebugMustNotFire() {
        assertSilent("BUG.DEBUG_STATEMENT", "src/api/index.js", "// console.log(x);\n");
        assertSilent("BUG.DEBUG_STATEMENT", "src/api/index.js", "/* console.log(x); */\n");
        assertSilent("BUG.DEBUG_STATEMENT", "scripts/run.py", "# print(x)\n");
    }

    @Test
    @DisplayName("相似标识符与测试文件不得报")
    void lookalikesMustNotFire() {
        assertSilent("BUG.DEBUG_STATEMENT", "scripts/run.py", "def f():\n    sprint(x)\n");
        assertSilent("BUG.DEBUG_STATEMENT", "src/utils/logger.js", "logger.log(x)\n");
        assertSilent("BUG.DEBUG_STATEMENT", "tests/console.spec.js", "console.log('probe')\n");
        assertSilent("BUG.DEBUG_STATEMENT", "src/api/index.js", "console.error(x); // eslint-disable-line\n");
    }

    // ==================== BUG.TODO_MARKER ====================

    @Test
    @DisplayName("BUG.TODO_MARKER 命中 Java 与配置文件里的未完成标记")
    void todoMarkerFires() {
        assertFires("BUG.TODO_MARKER", "src/A.java", "class A { // TO" + "DO: 补上校验\n}\n");
        assertFires("BUG.TODO_MARKER", "src/A.java", "# FI" + "XME 并发下会出错\n");
        assertFires("BUG.TODO_MARKER", "app.yml", "# HA" + "CK 临时绕过\nport: 8080\n");
    }

    @Test
    @DisplayName("带跟踪单号的标记是刻意留置，不得报")
    void trackedMarkerMustNotFire() {
        assertSilent("BUG.TODO_MARKER", "src/A.java", "// TO" + "DO(#123): 下个迭代处理\n");
        assertSilent("BUG.TODO_MARKER", "src/A.java", "// FI" + "XME(PROJ-456) 已知问题\n");
    }

    @Test
    @DisplayName("小写标识符与 Markdown 正文不得报")
    void lowercaseAndMarkdownMustNotFire() {
        assertSilent("BUG.TODO_MARKER", "src/A.java", "class A { List<String> to" + "doList = null; }\n");
        assertSilent("BUG.TODO_MARKER", "README.md", "- TO" + "DO：后续补充说明\n");
        assertSilent("BUG.TODO_MARKER", "application.yml", "api-key: sk-xx" + "xxx\n");
    }

    // ==================== BUG.INVALID_STRUCTURED_FILE ====================

    @Test
    @DisplayName("BUG.INVALID_STRUCTURED_FILE 命中写坏的 JSON / YAML / XML")
    void invalidStructuredFileFires() {
        assertFires("BUG.INVALID_STRUCTURED_FILE", "config/app.json", "{\n  \"a\": 1,\n  \"b\":\n}\n");
        assertFires("BUG.INVALID_STRUCTURED_FILE", "config/app.yml", "server:\n  port: 8080\n bad: 1\n");
        assertFires("BUG.INVALID_STRUCTURED_FILE", "config/app.xml", "<root><child></root>\n");
    }

    @Test
    @DisplayName("合法配置文件不得报（多文档 YAML、带 DOCTYPE 的映射文件、大体积 lock 文件）")
    void validStructuredFileMustNotFire() {
        assertSilent("BUG.INVALID_STRUCTURED_FILE", "k8s.yaml", "a: 1\n---\nb: 2\n");
        assertSilent("BUG.INVALID_STRUCTURED_FILE", "mapper/UserMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="x"><select id="f">SELECT 1</select></mapper>
                """);
    }

    // ==================== PERF.SQL_* ====================

    @Test
    @DisplayName("PERF.SQL_SELECT_STAR 命中 SELECT *，MyBatis 映射文件同样检查")
    void selectStarFires() {
        assertFires("PERF.SQL_SELECT_STAR", "db/query.sql", "SELECT * FROM users WHERE id = 1;\n");
        assertFires("PERF.SQL_SELECT_STAR", "mapper/UserMapper.xml",
                "<mapper namespace=\"x\"><select id=\"f\">SELECT * FROM users</select></mapper>\n");
    }

    @Test
    @DisplayName("显式列、count(*) 与普通数据 XML 不得报")
    void selectStarMustNotFire() {
        assertSilent("PERF.SQL_SELECT_STAR", "db/query.sql", "SELECT id, name FROM users;\n");
        assertSilent("PERF.SQL_SELECT_STAR", "db/query.sql", "SELECT count(*) FROM users;\n");
        assertSilent("PERF.SQL_SELECT_STAR", "db/query.sql", "-- SELECT * FROM users;\n");
        assertSilent("PERF.SQL_SELECT_STAR", "data/report.xml", "<note>SELECT * FROM users</note>\n");
    }

    @Test
    @DisplayName("PERF.SQL_WRITE_WITHOUT_WHERE 命中无条件的 UPDATE / DELETE")
    void writeWithoutWhereFires() {
        assertFires("PERF.SQL_WRITE_WITHOUT_WHERE", "db/cleanup.sql", "DELETE FROM sessions;\n");
        assertFires("PERF.SQL_WRITE_WITHOUT_WHERE", "db/cleanup.sql", "UPDATE users SET active = 1;\n");
    }

    @Test
    @DisplayName("带条件的写操作不得报，跨行语句也必须正确识别 WHERE")
    void writeWithWhereMustNotFire() {
        assertSilent("PERF.SQL_WRITE_WITHOUT_WHERE", "db/query.sql",
                "DELETE FROM sessions WHERE expired_at < now();\n");
        // 跨行是本规则最容易误报的形态：只看单行会把 UPDATE 那行判成没有 WHERE
        assertSilent("PERF.SQL_WRITE_WITHOUT_WHERE", "db/query.sql",
                "UPDATE users\nSET active = 1\nWHERE id = ?;\n");
        assertSilent("PERF.SQL_WRITE_WITHOUT_WHERE", "db/query.sql",
                "UPDATE users SET note = 'a; b' WHERE id = 1;\n");
    }

    @Test
    @DisplayName("迁移与初始化脚本整表重写是有意为之，不得报")
    void seedScriptsMustNotFire() {
        assertSilent("PERF.SQL_WRITE_WITHOUT_WHERE", "src/main/resources/db/migration/V1__init.sql",
                "DELETE FROM users;\n");
        assertSilent("PERF.SQL_WRITE_WITHOUT_WHERE", "db/data.sql", "DELETE FROM users;\n");
    }

    @Test
    @DisplayName("PERF.SQL_LEADING_WILDCARD 命中以 % 开头的 LIKE")
    void leadingWildcardFires() {
        assertFires("PERF.SQL_LEADING_WILDCARD", "db/query.sql",
                "SELECT id FROM users WHERE name LIKE '%张%';\n");
    }

    @Test
    @DisplayName("后置通配与匹配任意值不得报")
    void safeLikeMustNotFire() {
        assertSilent("PERF.SQL_LEADING_WILDCARD", "db/query.sql",
                "SELECT id FROM users WHERE name LIKE '张%';\n");
        assertSilent("PERF.SQL_LEADING_WILDCARD", "db/query.sql",
                "SELECT id FROM users WHERE name LIKE '%';\n");
    }

    // ==================== STYLE.* ====================

    @Test
    @DisplayName("STYLE.LINE_TOO_LONG 命中超长行，Java 文件同样检查")
    void lineTooLongFires() {
        String longLine = "int value = compute(" + "a, ".repeat(50) + "z);";
        assertTrue(longLine.length() > 120);
        assertFires("STYLE.LINE_TOO_LONG", "src/A.java", "class A {\n" + longLine + "\n}\n");
        assertFires("STYLE.LINE_TOO_LONG", "config/app.yml", "description: " + "很长的说明".repeat(40) + "\n");
    }

    @Test
    @DisplayName("无法断开的整块内容与 Markdown 不得报")
    void unbreakableLinesMustNotFire() {
        String url = "https://very-long-domain.example.org/" + "segment/".repeat(20) + "end";
        assertSilent("STYLE.LINE_TOO_LONG", "config/app.yml", "url: " + url + "\n");
        assertSilent("STYLE.LINE_TOO_LONG", "README.md", "| " + "列 |".repeat(60) + "\n");
    }

    @Test
    @DisplayName("STYLE.TRAILING_WHITESPACE 命中行尾空白，空行与 Markdown 不得报")
    void trailingWhitespaceRules() {
        assertFires("STYLE.TRAILING_WHITESPACE", "src/A.java", "class A {   \n}\n");
        assertFires("STYLE.TRAILING_WHITESPACE", "config/app.yml", "port: 8080\t\n");
        assertSilent("STYLE.TRAILING_WHITESPACE", "src/A.java", "class A {\n\n    int x = 1;\n}\n");
        // 空行带缩进极为常见，报告它只会刷屏
        assertSilent("STYLE.TRAILING_WHITESPACE", "src/A.java", "class A {\n    \n    int x = 1;\n}\n");
        // Markdown 行尾两个空格是硬换行语法
        assertSilent("STYLE.TRAILING_WHITESPACE", "README.md", "第一行  \n第二行\n");
    }

    @Test
    @DisplayName("CRLF 换行不得被当成行尾空白")
    void crlfMustNotFireTrailingWhitespace() {
        assertSilent("STYLE.TRAILING_WHITESPACE", "src/A.java", "class A {\r\n    int x = 1;\r\n}\r\n");
    }

    @Test
    @DisplayName("STYLE.MISSING_FINAL_NEWLINE 命中缺少末尾换行的文件")
    void missingFinalNewlineRules() {
        assertFires("STYLE.MISSING_FINAL_NEWLINE", "src/A.java", "class A {\n}");
        assertFires("STYLE.MISSING_FINAL_NEWLINE", "config/app.yml", "port: 8080");
        assertSilent("STYLE.MISSING_FINAL_NEWLINE", "src/A.java", "class A {\n}\n");
        assertSilent("STYLE.MISSING_FINAL_NEWLINE", "config/empty.yml", "");
    }

    // ==================== 语言隔离 ====================

    @Test
    @DisplayName("Java 的 AST 规则不得在 YAML 上执行")
    void javaRulesMustNotRunOnNonJava() {
        // 这段内容在 Java 里会命中 BUG.EMPTY_CATCH；YAML 里它只是普通文本
        assertSilent("BUG.EMPTY_CATCH", "config/app.yml", "code: \"try { g(); } catch (Exception e) { }\"\n");
        assertSilent("STYLE.METHOD_TOO_LONG", "config/app.yml", "code: \"void f() { \"\n");
    }

    @Test
    @DisplayName("文本规则不得越界到它未声明的语言：配置类规则不在 Java 上跑")
    void scopedTextRulesMustNotRunOnJava() {
        // 同样一行放在 YAML 里会命中，放在 Java 里不会（该规则只声明了 YAML/properties/TOML/Dockerfile）
        String line = "password: Pr0ductionP@ss\n";
        assertFires("SEC.CONFIG_PLAINTEXT_SECRET", "application.yml", line);
        assertSilent("SEC.CONFIG_PLAINTEXT_SECRET", "src/A.java", line);
    }

    @Test
    @DisplayName("语言无关的文本规则在 Java 上照样生效")
    void universalTextRulesRunOnJava() {
        assertFires("STYLE.TRAILING_WHITESPACE", "src/A.java", "class A {   \n}\n");
        assertFires("BUG.CONFLICT_MARKER", "src/A.java", "class A {\n<<<<<<< HEAD\n}\n>>>>>>> x\n");
        assertFires("BUG.TODO_MARKER", "src/A.java", "class A { // TO" + "DO 补上\n}\n");
    }

    @Test
    @DisplayName("规则总数与四维分布仍然满足下限（新增文本规则后）")
    void ruleCatalogStillMeetsBaseline() {
        var ids = ENGINE.rules().stream().map(Rule::id).collect(Collectors.toSet());
        assertTrue(ids.size() >= 57, "规则总数应不少于 57 条，实际 " + ids.size());
        long security = ENGINE.rules().stream()
                .filter(rule -> rule.type().name().equals("SECURITY")).count();
        long bug = ENGINE.rules().stream().filter(rule -> rule.type().name().equals("BUG")).count();
        long performance = ENGINE.rules().stream()
                .filter(rule -> rule.type().name().equals("PERFORMANCE")).count();
        long style = ENGINE.rules().stream().filter(rule -> rule.type().name().equals("STYLE")).count();
        assertTrue(bug >= 10 && security >= 10 && performance >= 10 && style >= 10,
                "四维各应不少于 10 条，实际 BUG=" + bug + " SEC=" + security
                        + " PERF=" + performance + " STYLE=" + style);
    }

    @Test
    @DisplayName("每条文本规则都声明了适用语言，且不含 UNKNOWN")
    void textRulesDeclareLanguages() {
        for (Rule rule : ENGINE.rules()) {
            assertFalse(rule.languages().isEmpty(), rule.id() + " 未声明适用语言");
            assertFalse(rule.languages().contains(com.cq.common.lang.Language.UNKNOWN),
                    rule.id() + " 不应把 UNKNOWN 列入适用范围");
        }
    }
}
