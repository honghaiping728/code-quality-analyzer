package com.cq.rule;

import com.cq.common.Issue;
import com.cq.parser.AstParserService;
import com.cq.parser.ParsedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎测试
 * <p>
 * 每条规则都配一组「正例命中」与「负例不误报」。**负例才是重点** ——
 * 规则引擎最常见的失败不是漏报，而是把正常代码刷屏导致使用者直接关掉引擎，
 * 因此 {@code *MustNotFireOn*} 这组用例承担了主要的回归保护职责。
 */
class RuleEngineTest {

    private static final AstParserService PARSER = new AstParserService();
    private static final RuleEngine ENGINE = new RuleEngine();

    // ==================== 工具 ====================

    /** 解析源码并跑全部规则，返回命中的规则 ID 集合 */
    private static Set<String> hits(String source) {
        ParsedFile file = PARSER.parseDetailed(source);
        assertTrue(file.hasUnit(), "测试源码本身应当能解析成功");
        return ENGINE.analyze(file).stream()
                .map(Issue::getRuleId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static void assertFires(String ruleId, String body) {
        Set<String> found = hits(wrap(body));
        assertTrue(found.contains(ruleId),
                "期望 " + ruleId + " 命中，实际命中: " + found);
    }

    private static void assertSilent(String ruleId, String body) {
        Set<String> found = hits(wrap(body));
        assertFalse(found.contains(ruleId),
                "期望 " + ruleId + " 不命中，但它误报了。全部命中: " + found);
    }

    /** 把方法体片段包成一个合法类 */
    private static String wrap(String body) {
        return "package t;\nimport java.util.*;\nimport java.io.*;\npublic class Sample {\n" + body + "\n}\n";
    }

    // ==================== Bug 维度：正例 ====================

    @Test
    @DisplayName("BUG.EMPTY_CATCH 命中空 catch")
    void emptyCatchFires() {
        assertFires("BUG.EMPTY_CATCH", """
                void f() { try { g(); } catch (Exception e) { } }
                void g() {}
                """);
    }

    @Test
    @DisplayName("BUG.PRINT_STACK_TRACE 命中仅打印堆栈的 catch")
    void printStackTraceFires() {
        assertFires("BUG.PRINT_STACK_TRACE", """
                void f() { try { g(); } catch (Exception e) { e.printStackTrace(); } }
                void g() {}
                """);
    }

    @Test
    @DisplayName("BUG.PRINT_STACK_TRACE 定位到 printStackTrace 那一行而非 catch 头")
    void printStackTraceReportsAtCallSite() {
        // 定位在 catch 头会导致代码片段里匹配不到 printStackTrace，
        // 下游依赖片段做确定性修复的模板就会失效，退化成调用大模型
        String source = wrap("""
                void f() {
                    try { g(); }
                    catch (Exception e) { e.printStackTrace(); }
                }
                void g() {}
                """);
        ParsedFile file = PARSER.parseDetailed(source);
        Issue issue = ENGINE.analyze(file).stream()
                .filter(i -> i.getRuleId().equals("BUG.PRINT_STACK_TRACE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未命中 BUG.PRINT_STACK_TRACE"));
        assertTrue(issue.getCodeSnippet().contains("printStackTrace"),
                "代码片段应包含 printStackTrace，实际为：" + issue.getCodeSnippet());
        // 从源码里推出该调用实际所在行，避免写死行号
        int expectedLine = 0;
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("printStackTrace")) {
                expectedLine = i + 1;
                break;
            }
        }
        assertEquals(expectedLine, issue.getLine(), "应报告在 e.printStackTrace() 所在行");
    }

    @Test
    @DisplayName("BUG.RETURN_IN_FINALLY 命中 finally 中的 return")
    void returnInFinallyFires() {
        assertFires("BUG.RETURN_IN_FINALLY", """
                int f() { try { return 1; } finally { return 2; } }
                """);
    }

    @Test
    @DisplayName("BUG.RESOURCE_LEAK 命中未关闭的资源")
    void resourceLeakFires() {
        assertFires("BUG.RESOURCE_LEAK", """
                void f() throws Exception { FileInputStream in = new FileInputStream("a.txt"); }
                """);
    }

    @Test
    @DisplayName("BUG.STRING_EQ_OPERATOR 命中字符串 == 比较")
    void stringEqualityFires() {
        assertFires("BUG.STRING_EQ_OPERATOR", """
                boolean f(String s) { return s == "x"; }
                """);
    }

    @Test
    @DisplayName("BUG.BIGDECIMAL_EQUALS 命中 BigDecimal.equals")
    void bigDecimalEqualsFires() {
        assertFires("BUG.BIGDECIMAL_EQUALS", """
                boolean f(java.math.BigDecimal a, java.math.BigDecimal b) { return a.equals(b); }
                """);
    }

    @Test
    @DisplayName("BUG.OPTIONAL_GET 命中未判空的 Optional.get")
    void optionalGetFires() {
        assertFires("BUG.OPTIONAL_GET", """
                Object f(Optional<String> o) { return o.get(); }
                """);
    }

    @Test
    @DisplayName("BUG.STATIC_DATE_FORMAT 命中静态 SimpleDateFormat")
    void staticDateFormatFires() {
        String source = """
                package t;
                import java.text.SimpleDateFormat;
                public class Sample {
                    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd");
                }
                """;
        assertTrue(hits(source).contains("BUG.STATIC_DATE_FORMAT"));
    }

    @Test
    @DisplayName("BUG.SELF_COMPARISON 命中整型自比较")
    void selfComparisonFires() {
        assertFires("BUG.SELF_COMPARISON", """
                boolean f(int a) { return a == a; }
                """);
    }

    @Test
    @DisplayName("BUG.DIVIDE_BY_ZERO 命中整数除零")
    void divideByZeroFires() {
        assertFires("BUG.DIVIDE_BY_ZERO", """
                int f(int a) { return a / 0; }
                """);
    }

    // ==================== Bug 维度：负例 ====================

    @Test
    @DisplayName("空格子里带注释的 catch 不算空 catch")
    void commentedCatchMustNotFire() {
        assertSilent("BUG.EMPTY_CATCH", """
                void f() { try { g(); } catch (IOException e) { /* 文件不存在时按空处理 */ } }
                void g() throws IOException {}
                """);
    }

    @Test
    @DisplayName("浮点自比较是 NaN 判断，不得报")
    void floatSelfComparisonMustNotFire() {
        assertSilent("BUG.SELF_COMPARISON", """
                boolean f(double x) { return x == x; }
                """);
    }

    @Test
    @DisplayName("x / 0.0 得 Infinity 而非异常，不得报")
    void floatDivideByZeroMustNotFire() {
        assertSilent("BUG.DIVIDE_BY_ZERO", """
                double f(double x) { return x / 0.0; }
                """);
    }

    @Test
    @DisplayName("x == null 是合法写法，不得报")
    void nullComparisonMustNotFire() {
        assertSilent("BUG.STRING_EQ_OPERATOR", """
                boolean f(String s) { return s == null; }
                """);
    }

    @Test
    @DisplayName("try-with-resources 中的资源不得报泄漏")
    void tryWithResourcesMustNotFire() {
        assertSilent("BUG.RESOURCE_LEAK", """
                void f() throws Exception {
                    try (FileInputStream in = new FileInputStream("a.txt")) { in.read(); }
                }
                """);
    }

    @Test
    @DisplayName("已调用 close 的资源不得报泄漏")
    void closedResourceMustNotFire() {
        assertSilent("BUG.RESOURCE_LEAK", """
                void f() throws Exception {
                    FileInputStream in = new FileInputStream("a.txt");
                    in.close();
                }
                """);
    }

    @Test
    @DisplayName("DateTimeFormatter 线程安全，不得报")
    void dateTimeFormatterMustNotFire() {
        String source = """
                package t;
                import java.time.format.DateTimeFormatter;
                public class Sample {
                    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_DATE;
                }
                """;
        assertFalse(hits(source).contains("BUG.STATIC_DATE_FORMAT"));
    }

    // ==================== 安全维度 ====================

    @Test
    @DisplayName("SEC.SQL_INJECTION 命中字符串拼接 SQL")
    void sqlInjectionFires() {
        assertFires("SEC.SQL_INJECTION", """
                void f(java.sql.Statement stmt, String name) throws Exception {
                    stmt.executeQuery("SELECT * FROM t WHERE name='" + name + "'");
                }
                """);
    }

    @Test
    @DisplayName("拼接整型不构成注入，不得报")
    void sqlInjectionOnIntMustNotFire() {
        assertSilent("SEC.SQL_INJECTION", """
                void f(java.sql.Statement stmt, int id) throws Exception {
                    stmt.executeQuery("SELECT * FROM t WHERE id=" + id);
                }
                """);
    }

    @Test
    @DisplayName("SEC.HARDCODED_CREDENTIAL 命中硬编码口令")
    void hardcodedCredentialFires() {
        assertFires("SEC.HARDCODED_CREDENTIAL", """
                String dbPassword = "s3cr3tP@ssw0rd";
                """);
    }

    @Test
    @DisplayName("占位符凭证不得报")
    void placeholderCredentialMustNotFire() {
        assertSilent("SEC.HARDCODED_CREDENTIAL", """
                String password = "${DB_PASSWORD}";
                """);
    }

    @Test
    @DisplayName("SEC.COMMAND_INJECTION 命中动态命令参数")
    void commandInjectionFires() {
        assertFires("SEC.COMMAND_INJECTION", """
                void f(String userInput) throws Exception { Runtime.getRuntime().exec(userInput); }
                """);
    }

    @Test
    @DisplayName("常量命令参数不得报")
    void constantCommandMustNotFire() {
        assertSilent("SEC.COMMAND_INJECTION", """
                static final String CMD = "ls";
                void f() throws Exception { Runtime.getRuntime().exec(CMD); }
                """);
    }

    @Test
    @DisplayName("SEC.WEAK_HASH 命中 MD5")
    void weakHashFires() {
        assertFires("SEC.WEAK_HASH", """
                Object f() throws Exception { return java.security.MessageDigest.getInstance("MD5"); }
                """);
    }

    @Test
    @DisplayName("SHA-256 不得报弱哈希")
    void sha256MustNotFire() {
        assertSilent("SEC.WEAK_HASH", """
                Object f() throws Exception { return java.security.MessageDigest.getInstance("SHA-256"); }
                """);
    }

    @Test
    @DisplayName("SEC.INSECURE_RANDOM 命中用于令牌的 Random")
    void insecureRandomFires() {
        // 该规则刻意收窄到「变量名暗示安全用途」才报告，因为「是否用于安全场景」
        // 在语法层不可判定，泛泛地报 new Random() 会把大量正常代码卷进来
        assertFires("SEC.INSECURE_RANDOM", """
                String f() { Random token = new Random(); return String.valueOf(token.nextInt()); }
                """);
    }

    @Test
    @DisplayName("普通用途的 Random 不得报")
    void ordinaryRandomMustNotFire() {
        assertSilent("SEC.INSECURE_RANDOM", """
                int f() { Random random = new Random(); return random.nextInt(); }
                """);
    }

    @Test
    @DisplayName("SEC.XXE 命中未加固的 XML 解析器")
    void xxeFires() {
        assertFires("SEC.XXE", """
                void f() throws Exception {
                    javax.xml.parsers.DocumentBuilderFactory factory =
                        javax.xml.parsers.DocumentBuilderFactory.newInstance();
                }
                """);
    }

    @Test
    @DisplayName("已禁用外部实体的解析器不得报")
    void hardenedXmlMustNotFire() {
        assertSilent("SEC.XXE", """
                void f() throws Exception {
                    javax.xml.parsers.DocumentBuilderFactory factory =
                        javax.xml.parsers.DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                }
                """);
    }

    @Test
    @DisplayName("SEC.INSECURE_DESERIALIZATION 命中 ObjectInputStream")
    void insecureDeserializationFires() {
        assertFires("SEC.INSECURE_DESERIALIZATION", """
                Object f(InputStream in) throws Exception { return new ObjectInputStream(in).readObject(); }
                """);
    }

    @Test
    @DisplayName("领域对象自带 readObject 不得报")
    void domainReadObjectMustNotFire() {
        assertSilent("SEC.INSECURE_DESERIALIZATION", """
                void readObject(ObjectInputStream in) throws Exception { in.defaultReadObject(); }
                """);
    }

    @Test
    @DisplayName("SEC.TRUST_ALL_CERT 命中空实现的 TrustManager")
    void trustAllCertFires() {
        assertFires("SEC.TRUST_ALL_CERT", """
                Object f() {
                    return new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    };
                }
                """);
    }

    @Test
    @DisplayName("SEC.SENSITIVE_LOG 命中日志中的敏感字段")
    void sensitiveLogFires() {
        assertFires("SEC.SENSITIVE_LOG", """
                void f(String password) { log.info("user login {}", password); }
                """);
    }

    @Test
    @DisplayName("日志中的计数字段不得报")
    void logCountMustNotFire() {
        assertSilent("SEC.SENSITIVE_LOG", """
                void f(int tokenCount) { log.info("processed {}", tokenCount); }
                """);
    }

    @Test
    @DisplayName("SEC.ECB_MODE 命中 AES 默认 ECB")
    void ecbModeFires() {
        assertFires("SEC.ECB_MODE", """
                Object f() throws Exception { return javax.crypto.Cipher.getInstance("AES"); }
                """);
    }

    @Test
    @DisplayName("GCM 模式不得报")
    void gcmMustNotFire() {
        assertSilent("SEC.ECB_MODE", """
                Object f() throws Exception { return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); }
                """);
    }

    // ==================== 性能维度 ====================

    @Test
    @DisplayName("PERF.DB_IN_LOOP 命中循环内查库")
    void dbCallInLoopFires() {
        assertFires("PERF.DB_IN_LOOP", """
                void f(List<Long> ids, UserRepository repo) {
                    for (Long id : ids) { repo.findById(id); }
                }
                """);
    }

    @Test
    @DisplayName("循环体内 lambda 中的调用是延迟执行，不得报")
    void lambdaInLoopMustNotFire() {
        assertSilent("PERF.DB_IN_LOOP", """
                void f(List<Long> ids, UserRepository repo) {
                    for (Long id : ids) { ids.forEach(x -> repo.findById(x)); }
                }
                """);
    }

    @Test
    @DisplayName("循环内访问 Map 是内存操作，不得报查库")
    void mapGetInLoopMustNotFire() {
        assertSilent("PERF.DB_IN_LOOP", """
                void f(List<String> keys, Map<String, String> cache) {
                    for (String key : keys) { cache.get(key); }
                }
                """);
    }

    @Test
    @DisplayName("把查询结果直接作为遍历对象只查一次，不得报 N+1")
    void queryAsLoopIterableMustNotFire() {
        // for (Row r : mapper.selectList(null)) 里的查询在语法上位于 ForEachStmt 内，
        // 但它只执行一次，不是每轮迭代都查库
        assertSilent("PERF.DB_IN_LOOP", """
                void f(UserMapper mapper) {
                    for (Object row : mapper.selectList(null)) { use(row); }
                }
                void use(Object o) {}
                """);
    }

    @Test
    @DisplayName("PERF.STRING_CONCAT_IN_LOOP 命中循环内字符串拼接")
    void stringConcatInLoopFires() {
        assertFires("PERF.STRING_CONCAT_IN_LOOP", """
                String f(List<String> xs) {
                    String s = "";
                    for (String x : xs) { s += x; }
                    return s;
                }
                """);
    }

    @Test
    @DisplayName("数值累加不得报字符串拼接")
    void numericAccumulationMustNotFire() {
        assertSilent("PERF.STRING_CONCAT_IN_LOOP", """
                int f(List<Integer> xs) {
                    int total = 0;
                    for (Integer x : xs) { total += x; }
                    return total;
                }
                """);
    }

    @Test
    @DisplayName("PERF.LOG_CONCAT 命中日志字符串拼接")
    void logConcatFires() {
        assertFires("PERF.LOG_CONCAT", """
                void f(String userId) { log.info("user=" + userId); }
                """);
    }

    @Test
    @DisplayName("PERF.PATTERN_COMPILE_IN_LOOP 命中循环内编译正则")
    void patternCompileInLoopFires() {
        assertFires("PERF.PATTERN_COMPILE_IN_LOOP", """
                void f(List<String> xs) {
                    for (String x : xs) { Pattern.compile("[a-z]+").matcher(x).matches(); }
                }
                """);
    }

    @Test
    @DisplayName("PERF.CONTAINS_IN_LOOP 命中循环内 List.contains")
    void containsInLoopFires() {
        assertFires("PERF.CONTAINS_IN_LOOP", """
                void f(List<String> xs, List<String> other) {
                    for (String x : xs) { other.contains(x); }
                }
                """);
    }

    @Test
    @DisplayName("循环内 String.contains 不是 O(n²)，不得报")
    void stringContainsInLoopMustNotFire() {
        assertSilent("PERF.CONTAINS_IN_LOOP", """
                void f(List<String> xs) {
                    for (String x : xs) { x.contains("a"); }
                }
                """);
    }

    @Test
    @DisplayName("PERF.NEW_STRING 命中 new String 字面量")
    void newStringFires() {
        assertFires("PERF.NEW_STRING", """
                String f() { return new String("abc"); }
                """);
    }

    @Test
    @DisplayName("new String(bytes) 是合法用法，不得报")
    void newStringFromBytesMustNotFire() {
        assertSilent("PERF.NEW_STRING", """
                String f(byte[] bytes) { return new String(bytes); }
                """);
    }

    @Test
    @DisplayName("PERF.SLEEP_IN_LOOP 命中循环内休眠")
    void sleepInLoopFires() {
        assertFires("PERF.SLEEP_IN_LOOP", """
                void f() throws Exception { for (int i = 0; i < 3; i++) { Thread.sleep(100); } }
                """);
    }

    @Test
    @DisplayName("PERF.DATE_FORMAT_IN_LOOP 命中循环内创建格式化器")
    void dateFormatInLoopFires() {
        assertFires("PERF.DATE_FORMAT_IN_LOOP", """
                void f(List<Date> dates) {
                    for (Date d : dates) { new SimpleDateFormat("yyyy-MM-dd").format(d); }
                }
                """);
    }

    @Test
    @DisplayName("PERF.COLLECTION_COPY_IN_LOOP 命中循环内集合拷贝")
    void collectionCopyInLoopFires() {
        assertFires("PERF.COLLECTION_COPY_IN_LOOP", """
                void f(List<List<String>> groups) {
                    for (List<String> g : groups) { new ArrayList<>(g); }
                }
                """);
    }

    @Test
    @DisplayName("PERF.STRING_FORMAT_IN_LOOP 命中循环内格式化")
    void stringFormatInLoopFires() {
        assertFires("PERF.STRING_FORMAT_IN_LOOP", """
                void f(List<Integer> xs) {
                    for (Integer x : xs) { String.format("%d", x); }
                }
                """);
    }

    // ==================== 规范维度 ====================

    @Test
    @DisplayName("STYLE.METHOD_TOO_LONG 命中超长方法")
    void methodTooLongFires() {
        StringBuilder body = new StringBuilder("void f() {\n");
        for (int i = 0; i < 60; i++) {
            body.append("    int v").append(i).append(" = ").append(i).append(";\n");
        }
        body.append("}\n");
        assertFires("STYLE.METHOD_TOO_LONG", body.toString());
    }

    @Test
    @DisplayName("STYLE.COMPLEXITY_TOO_HIGH 命中高复杂度方法")
    void complexityTooHighFires() {
        StringBuilder body = new StringBuilder("void f(int a) {\n");
        for (int i = 0; i < 11; i++) {
            body.append("    if (a == ").append(i).append(") { g(); }\n");
        }
        body.append("}\nvoid g() {}\n");
        assertFires("STYLE.COMPLEXITY_TOO_HIGH", body.toString());
    }

    @Test
    @DisplayName("STYLE.TOO_MANY_PARAMS 命中参数过多")
    void tooManyParametersFires() {
        assertFires("STYLE.TOO_MANY_PARAMS", """
                void f(int a, int b, int c, int d, int e, int g) { }
                """);
    }

    @Test
    @DisplayName("STYLE.CLASS_NAMING 命中非大驼峰类名")
    void classNamingFires() {
        String source = "package t;\npublic class my_class { }\n";
        assertTrue(hits(source).contains("STYLE.CLASS_NAMING"));
    }

    @Test
    @DisplayName("STYLE.METHOD_NAMING 命中非小驼峰方法名")
    void methodNamingFires() {
        assertFires("STYLE.METHOD_NAMING", """
                void Bad_Method() { }
                """);
    }

    @Test
    @DisplayName("构造函数名等于类名，不得报方法命名")
    void constructorNameMustNotFire() {
        String source = "package t;\npublic class Sample {\n    public Sample() { }\n}\n";
        assertFalse(hits(source).contains("STYLE.METHOD_NAMING"));
    }

    @Test
    @DisplayName("STYLE.CONSTANT_NAMING 命中非大写下划线常量")
    void constantNamingFires() {
        assertFires("STYLE.CONSTANT_NAMING", """
                private static final int maxRetry = 3;
                """);
    }

    @Test
    @DisplayName("static final Logger log 是惯用写法，不得报")
    void loggerConstantMustNotFire() {
        assertSilent("STYLE.CONSTANT_NAMING", """
                private static final Logger log = null;
                """);
    }

    @Test
    @DisplayName("serialVersionUID 是必须字段，不得报")
    void serialVersionUidMustNotFire() {
        assertSilent("STYLE.CONSTANT_NAMING", """
                private static final long serialVersionUID = 1L;
                """);
    }

    @Test
    @DisplayName("STYLE.MAGIC_NUMBER 命中条件中的魔法数字")
    void magicNumberFires() {
        assertFires("STYLE.MAGIC_NUMBER", """
                void f(int status) { if (status == 42) { g(); } }
                void g() {}
                """);
    }

    @Test
    @DisplayName("白名单中的数值（0/1/2）不得报")
    void allowlistedNumberMustNotFire() {
        assertSilent("STYLE.MAGIC_NUMBER", """
                void f(int status) { if (status == 1) { g(); } }
                void g() {}
                """);
    }

    @Test
    @DisplayName("STYLE.DEEP_NESTING 命中四层嵌套")
    void deepNestingFires() {
        assertFires("STYLE.DEEP_NESTING", """
                void f(int a) {
                    if (a > 0) { for (int i = 0; i < 2; i++) { while (a > 1) { try { g(); } catch (Exception e) { } } } }
                }
                void g() throws Exception {}
                """);
    }

    @Test
    @DisplayName("else if 阶梯是一个判断链，不得报嵌套过深")
    void elseIfLadderMustNotFire() {
        assertSilent("STYLE.DEEP_NESTING", """
                void f(int a) {
                    if (a == 1) { g(); }
                    else if (a == 2) { g(); }
                    else if (a == 3) { g(); }
                    else if (a == 4) { g(); }
                    else if (a == 5) { g(); }
                }
                void g() {}
                """);
    }

    @Test
    @DisplayName("STYLE.MISSING_JAVADOC 命中缺注释的 public 方法")
    void missingJavadocFires() {
        assertFires("STYLE.MISSING_JAVADOC", """
                public void doSomething() { }
                """);
    }

    @Test
    @DisplayName("@Override 方法豁免 Javadoc")
    void overrideMustNotFire() {
        assertSilent("STYLE.MISSING_JAVADOC", """
                @Override public void doSomething() { }
                """);
    }

    @Test
    @DisplayName("getter/setter 豁免 Javadoc")
    void accessorMustNotFire() {
        assertSilent("STYLE.MISSING_JAVADOC", """
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                """);
    }

    @Test
    @DisplayName("STYLE.UNUSED_IMPORT 命中未使用的 import")
    void unusedImportFires() {
        String source = """
                package t;
                import java.util.List;
                public class Sample {
                    void f() { }
                }
                """;
        assertTrue(hits(source).contains("STYLE.UNUSED_IMPORT"));
    }

    @Test
    @DisplayName("已使用的 import 不得报")
    void usedImportMustNotFire() {
        String source = """
                package t;
                import java.util.List;
                public class Sample {
                    List<String> f() { return null; }
                }
                """;
        assertFalse(hits(source).contains("STYLE.UNUSED_IMPORT"));
    }

    @Test
    @DisplayName("仅被注解使用的 import 不得报")
    void annotationOnlyImportMustNotFire() {
        // 注解在 JavaParser 中是 Name 而非 SimpleName，只查 SimpleName 会误报
        String source = """
                package t;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface SampleMapper {
                }
                """;
        assertFalse(hits(source).contains("STYLE.UNUSED_IMPORT"),
                "被 @Mapper 使用的 import 不应被判为未使用");
    }

    @Test
    @DisplayName("仅被 Javadoc {@link} 引用的 import 不得报")
    void javadocReferencedImportMustNotFire() {
        String source = """
                package t;
                import java.util.List;
                /** 说明：见 {@link List} 的用法 */
                public class Sample {
                }
                """;
        assertFalse(hits(source).contains("STYLE.UNUSED_IMPORT"));
    }

    @Test
    @DisplayName("STYLE.SHORT_FIELD_NAME 命中过短字段名")
    void shortFieldNameFires() {
        assertFires("STYLE.SHORT_FIELD_NAME", """
                private int q;
                """);
    }

    @Test
    @DisplayName("局部变量短名不得报（只查字段）")
    void shortLocalVariableMustNotFire() {
        assertSilent("STYLE.SHORT_FIELD_NAME", """
                void f() { int q = 1; }
                """);
    }

    // ==================== 引擎行为 ====================

    @Test
    @DisplayName("规则 ID 唯一且总数满足 40 条以上")
    void ruleCatalogIsWellFormed() {
        var ids = ENGINE.rules().stream().map(Rule::id).collect(Collectors.toSet());
        assertEquals(ENGINE.rules().size(), ids.size(), "存在重复的规则 ID");
        assertTrue(ids.size() >= 40, "规则总数应不少于 40 条，实际 " + ids.size());

        long bug = ENGINE.rules().stream().filter(r -> r.type().name().equals("BUG")).count();
        long security = ENGINE.rules().stream().filter(r -> r.type().name().equals("SECURITY")).count();
        long performance = ENGINE.rules().stream().filter(r -> r.type().name().equals("PERFORMANCE")).count();
        long style = ENGINE.rules().stream().filter(r -> r.type().name().equals("STYLE")).count();
        assertTrue(bug >= 10, "Bug 规则应不少于 10 条，实际 " + bug);
        assertTrue(security >= 10, "安全规则应不少于 10 条，实际 " + security);
        assertTrue(performance >= 10, "性能规则应不少于 10 条，实际 " + performance);
        assertTrue(style >= 10, "规范规则应不少于 10 条，实际 " + style);
    }

    @Test
    @DisplayName("每条规则都必须声明规则 ID、名称与说明")
    void everyRuleHasMetadata() {
        for (Rule rule : ENGINE.rules()) {
            assertTrue(rule.id() != null && !rule.id().isBlank(), "规则 ID 不能为空");
            assertTrue(rule.id().contains("."), "规则 ID 应带命名空间，如 BUG.EMPTY_CATCH：" + rule.id());
            assertTrue(rule.name() != null && !rule.name().isBlank(), rule.id() + " 缺少名称");
            assertTrue(rule.description() != null && !rule.description().isBlank(), rule.id() + " 缺少说明");
            assertTrue(rule.confidence() > 0 && rule.confidence() <= 1.0,
                    rule.id() + " 置信度应在 (0,1] 区间，实际 " + rule.confidence());
        }
    }

    @Test
    @DisplayName("语法错误的源码不应抛异常，解析问题被记录而非中断")
    void brokenSourceIsHandledGracefully() {
        ParsedFile file = PARSER.parseDetailed("package t; class A { void f( { } }");
        // JavaParser 有一定容错能力，可能仍产出部分 AST；无论是否产出，
        // 解析问题都应被记录，且规则引擎都不能抛异常
        assertFalse(file.getSummary().getParseErrors().isEmpty(), "语法错误应被记录到 parseErrors");
        assertTrue(ENGINE.analyze(file) != null, "规则引擎面对残缺 AST 不应抛异常");
    }

    @Test
    @DisplayName("禁用的规则不再产出问题")
    void disabledRuleProducesNothing() {
        String source = wrap("void f() { try { g(); } catch (Exception e) { } }\nvoid g() {}");
        ParsedFile file = PARSER.parseDetailed(source);
        assertTrue(ENGINE.analyze(file).stream().anyMatch(i -> i.getRuleId().equals("BUG.EMPTY_CATCH")));

        var config = new com.cq.common.model.RuleConfigSet();
        var ruleConfig = new com.cq.common.model.RuleConfig("BUG.EMPTY_CATCH", "空 catch", "BUG");
        ruleConfig.setEnabled(false);
        config.getRules().put("BUG.EMPTY_CATCH", ruleConfig);

        assertTrue(ENGINE.analyze(file, config).stream()
                .noneMatch(i -> i.getRuleId().equals("BUG.EMPTY_CATCH")));
    }

    @Test
    @DisplayName("同一行同一规则只产出一条问题")
    void duplicateFindingsAreMerged() {
        ParsedFile file = PARSER.parseDetailed(wrap("int f(int a) { return a / 0; }"));
        var issues = ENGINE.analyze(file).stream()
                .filter(i -> i.getRuleId().equals("BUG.DIVIDE_BY_ZERO"))
                .collect(Collectors.toList());
        assertEquals(1, issues.size(), "同一处除零应只报告一次");
    }
}
