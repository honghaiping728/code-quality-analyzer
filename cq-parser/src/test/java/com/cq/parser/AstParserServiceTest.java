package com.cq.parser;

import com.cq.common.ast.AstSummary;
import com.cq.common.ast.CallGraphEdge;
import com.cq.common.ast.ClassInfo;
import com.cq.common.ast.MethodCall;
import com.cq.common.ast.MethodInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST 解析服务测试
 * <p>
 * 覆盖摘要模型的全部产出面：包与 import、类型结构（含匿名类与枚举常量体）、
 * 方法签名、调用图启发式解析、派生统计与容错行为。
 * <p>
 * 调用图部分是重点：规则引擎的「循环内查库」「SQL 注入」等规则依赖 scope 与
 * resolved 的判定，一旦解析退化（例如重载区分失效），受影响的是一批规则而不是单条规则。
 */
class AstParserServiceTest {

    private static final AstParserService PARSER = new AstParserService();

    // ==================== 工具 ====================

    /** 解析源码并断言语法正确（测试源码本身出错会让断言结果失去意义） */
    private static AstSummary parse(String source) {
        AstSummary summary = PARSER.parse(source);
        assertTrue(summary.isParsed(), "测试源码本身应当能解析成功: " + summary.getParseErrors());
        return summary;
    }

    private static MethodInfo method(AstSummary summary, String name) {
        return summary.getMethods().stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到方法 " + name + "，实际为: " + summary.getMethods()));
    }

    private static ClassInfo classInfo(AstSummary summary, String name) {
        return summary.getClasses().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到类型 " + name + "，实际为: " + summary.getClasses()));
    }

    private static MethodCall call(MethodInfo method, String callName) {
        return method.getCalls().stream()
                .filter(c -> c.getMethodName().equals(callName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        method.getName() + " 内未找到调用 " + callName + "，实际为: " + method.getCalls()));
    }

    private static List<CallGraphEdge> edgesFrom(AstSummary summary, MethodInfo method) {
        return summary.getCallGraph().getEdges().stream()
                .filter(edge -> edge.getFrom().equals(method.signature()))
                .collect(Collectors.toList());
    }

    // ==================== 包与 import ====================

    @Test
    @DisplayName("提取包名与 import，静态导入带 static 前缀")
    void extractsPackageAndImports() {
        AstSummary summary = parse("""
                package com.demo.app;
                import java.util.List;
                import static java.util.Collections.emptyList;
                class A {
                    List<String> names = emptyList();
                }
                """);
        assertEquals("com.demo.app", summary.getPackageName());
        assertEquals(List.of("java.util.List", "static java.util.Collections.emptyList"), summary.getImports());
    }

    @Test
    @DisplayName("默认包的包名为空串而非 null")
    void defaultPackageIsEmptyString() {
        AstSummary summary = parse("class A { }");
        assertEquals("", summary.getPackageName());
        assertTrue(summary.getImports().isEmpty());
    }

    // ==================== 类型结构 ====================

    @Test
    @DisplayName("类/接口/枚举/记录/注解的类型与顺序均被提取")
    void extractsAllTypeKinds() {
        AstSummary summary = parse("""
                package t;
                public class A { }
                interface B { }
                enum C { X }
                record D(int x) { }
                @interface E { }
                """);
        assertEquals(List.of("A", "B", "C", "D", "E"),
                summary.getClasses().stream().map(ClassInfo::getName).toList(),
                "类型列表应按起始行排序");
        assertEquals("CLASS", classInfo(summary, "A").getKind());
        assertEquals("INTERFACE", classInfo(summary, "B").getKind());
        assertEquals("ENUM", classInfo(summary, "C").getKind());
        assertEquals("RECORD", classInfo(summary, "D").getKind());
        assertEquals("ANNOTATION", classInfo(summary, "E").getKind());
    }

    @Test
    @DisplayName("修饰符与 extends/implements 都记入类型信息")
    void extractsModifiersAndParentTypes() {
        AstSummary summary = parse("""
                package t;
                public abstract class A extends B implements C, D { }
                """);
        ClassInfo info = classInfo(summary, "A");
        assertEquals(List.of("public", "abstract"), info.getModifiers());
        assertEquals(List.of("B", "C", "D"), info.getExtendedTypes(),
                "当前实现把 implements 也归入 extendedTypes");
    }

    @Test
    @DisplayName("嵌套类的全限定名由外到内拼接")
    void nestedClassHasQualifiedName() {
        AstSummary summary = parse("""
                package p;
                class Outer {
                    static class Inner {
                        void m() { }
                    }
                }
                """);
        assertEquals("p.Outer", classInfo(summary, "Outer").getQualifiedName());
        ClassInfo inner = classInfo(summary, "Inner");
        assertEquals("p.Outer.Inner", inner.getQualifiedName());
        assertTrue(inner.getModifiers().contains("static"));
        MethodInfo m = method(summary, "m");
        assertEquals("Inner", m.getClassName());
        assertEquals("p.Outer.Inner", m.getClassQualifiedName());
    }

    @Test
    @DisplayName("匿名类成为独立类型，方法不再挂到外层类")
    void anonymousClassBecomesItsOwnType() {
        AstSummary summary = parse("""
                package t;
                public class Outer {
                    public void start() {
                        Runnable r = new Runnable() {
                            public void run() { helper(); }
                        };
                    }
                    void helper() { }
                }
                """);
        ClassInfo anonymous = classInfo(summary, "Outer$1");
        assertEquals("ANONYMOUS", anonymous.getKind());
        assertEquals(List.of("Runnable"), anonymous.getExtendedTypes());
        assertEquals("t.Outer$1", anonymous.getQualifiedName());

        MethodInfo run = method(summary, "run");
        assertEquals("Outer$1", run.getClassName());
        assertEquals("t.Outer$1", run.getClassQualifiedName());

        // 匿名类体内的调用不计入外层方法
        assertTrue(method(summary, "start").getCalls().isEmpty(),
                "start 的调用列表不应包含匿名类体里的 helper()");

        // 匿名类方法内的调用只在匿名类内查找，不向外部类回退
        MethodCall helper = call(run, "helper");
        assertFalse(helper.isResolved());
        assertEquals("helper", helper.getTarget());
    }

    @Test
    @DisplayName("枚举常量体成为独立类型，常量名记在父类型上")
    void enumConstantBodyBecomesItsOwnType() {
        AstSummary summary = parse("""
                package t;
                enum Status {
                    ACTIVE {
                        void ping() { }
                    },
                    OFF;
                }
                """);
        assertEquals(2, summary.getClasses().size(), "只有 Status 与 Status$1，无类体的常量不产生类型");

        ClassInfo constantType = classInfo(summary, "Status$1");
        assertEquals("ANONYMOUS", constantType.getKind());
        assertEquals(List.of("Status$ACTIVE"), constantType.getExtendedTypes());
        assertEquals("t.Status$1", constantType.getQualifiedName());

        MethodInfo ping = method(summary, "ping");
        assertEquals("Status$1", ping.getClassName());
        assertEquals("t.Status$1", ping.getClassQualifiedName());
    }

    // ==================== 方法提取 ====================

    @Test
    @DisplayName("方法签名细节：返回类型/参数/可变参数/throws/修饰符")
    void extractsMethodSignatureDetails() {
        AstSummary summary = parse("""
                package t;
                import java.io.IOException;
                import java.util.List;
                public class Svc {
                    public static List<String> find(String name, int... ids) throws IOException {
                        return null;
                    }
                }
                """);
        MethodInfo find = method(summary, "find");
        assertEquals("List<String>", find.getReturnType());
        assertFalse(find.isConstructor());
        assertTrue(find.isStaticMethod());
        assertEquals(List.of("public", "static"), find.getModifiers());
        assertEquals(List.of("IOException"), find.getThrownExceptions());
        assertEquals("Svc", find.getClassName());
        assertEquals("t.Svc", find.getClassQualifiedName());
        assertEquals("t.Svc#find(String,int)", find.signature(),
                "可变参数的类型只记元素类型，varargs 由参数自身的标记表达");

        assertEquals(2, find.getParameters().size());
        assertEquals("String", find.getParameters().get(0).getType());
        assertEquals("name", find.getParameters().get(0).getName());
        assertFalse(find.getParameters().get(0).isVarArgs());
        assertEquals("int", find.getParameters().get(1).getType());
        assertEquals("ids", find.getParameters().get(1).getName());
        assertTrue(find.getParameters().get(1).isVarArgs());
    }

    @Test
    @DisplayName("构造函数：名字为类名、返回类型为 null、标记为构造器")
    void extractsConstructor() {
        AstSummary summary = parse("""
                package t;
                class A {
                    private final int x;
                    public A(int x) { this.x = x; }
                }
                """);
        assertEquals(1, summary.getMethodCount(), "字段不应被当作方法");
        MethodInfo constructor = method(summary, "A");
        assertTrue(constructor.isConstructor());
        assertNull(constructor.getReturnType());
        assertEquals(List.of("public"), constructor.getModifiers());
        assertEquals("t.A#A(int)", constructor.signature());
        assertTrue(constructor.getCalls().isEmpty(), "this.x = x 是赋值而非调用");
    }

    @Test
    @DisplayName("接口方法没有方法体，圈复杂度保持 1")
    void interfaceMethodHasComplexityOne() {
        AstSummary summary = parse("""
                package t;
                interface I {
                    void run();
                    int compute(int a);
                }
                """);
        assertEquals(2, summary.getMethodCount());
        for (MethodInfo m : summary.getMethods()) {
            assertEquals(1, m.getCyclomaticComplexity(), m.getName() + " 没有方法体，圈复杂度应为 1");
            assertTrue(m.getCalls().isEmpty());
        }
    }

    @Test
    @DisplayName("方法行号范围与实际源码一致")
    void methodLinesMatchSource() {
        AstSummary summary = parse("""
                package t;
                class A {

                    void first() {
                    }

                    void second() {
                    }
                }
                """);
        MethodInfo first = method(summary, "first");
        assertEquals(4, first.getStartLine());
        assertEquals(5, first.getEndLine());
        MethodInfo second = method(summary, "second");
        assertEquals(7, second.getStartLine());
        assertEquals(8, second.getEndLine());
    }

    @Test
    @DisplayName("方法与类型列表按起始行升序排列")
    void methodsAndClassesSortedByStartLine() {
        AstSummary summary = parse("""
                package t;
                class Outer {
                    void a() { }
                    static class Inner {
                        void b() { }
                    }
                    void c() { }
                }
                """);
        assertEquals(List.of("a", "b", "c"),
                summary.getMethods().stream().map(MethodInfo::getName).toList());
        assertEquals(List.of("Outer", "Inner"),
                summary.getClasses().stream().map(ClassInfo::getName).toList());
        for (int i = 1; i < summary.getMethods().size(); i++) {
            assertTrue(summary.getMethods().get(i - 1).getStartLine()
                            <= summary.getMethods().get(i).getStartLine(),
                    "方法列表未按行号升序");
        }
    }

    // ==================== 调用图 ====================

    @Test
    @DisplayName("无 scope 与 this. 的调用解析到本类方法，行号指向调用点")
    void resolvesThisAndUnqualifiedCalls() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void a() {
                        b();
                        this.c();
                    }
                    void b() { }
                    void c() { }
                }
                """);
        MethodInfo a = method(summary, "a");
        MethodCall bCall = call(a, "b");
        assertTrue(bCall.isResolved());
        assertEquals("t.A#b()", bCall.getTarget());
        assertNull(bCall.getScope());

        MethodCall cCall = call(a, "c");
        assertTrue(cCall.isResolved());
        assertEquals("t.A#c()", cCall.getTarget());
        assertEquals("this", cCall.getScope());

        List<CallGraphEdge> edges = edgesFrom(summary, a);
        assertEquals(2, edges.size());
        assertEquals(4, edges.get(0).getLine(), "b() 在第 4 行");
        assertEquals(5, edges.get(1).getLine(), "this.c() 在第 5 行");
        assertTrue(edges.stream().allMatch(CallGraphEdge::isResolved));
    }

    @Test
    @DisplayName("ClassName.method() 中 ClassName 为本文件类型时解析成功")
    void resolvesQualifiedCallToTypeInSameFile() {
        AstSummary summary = parse("""
                package t;
                class A {
                    static void s() { }
                    void a() { A.s(); }
                }
                """);
        MethodCall s = call(method(summary, "a"), "s");
        assertTrue(s.isResolved());
        assertEquals("t.A#s()", s.getTarget());
        assertEquals("A", s.getScope());
    }

    @Test
    @DisplayName("重载按实参个数区分")
    void disambiguatesOverloadsByArgumentCount() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void f() {
                        g(1);
                        g(1, 2);
                    }
                    void g(int a) { }
                    void g(int a, int b) { }
                }
                """);
        List<MethodCall> calls = method(summary, "f").getCalls();
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).isResolved());
        assertEquals("t.A#g(int)", calls.get(0).getTarget());
        assertTrue(calls.get(1).isResolved());
        assertEquals("t.A#g(int,int)", calls.get(1).getTarget());
    }

    @Test
    @DisplayName("实参个数相同的重载无法区分时保持未解析，不退化为错误命中")
    void unresolvableOverloadStaysUnresolved() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void f() { g(1); }
                    void g(int a) { }
                    void g(String s) { }
                }
                """);
        MethodCall g = call(method(summary, "f"), "g");
        assertFalse(g.isResolved());
        assertEquals("g", g.getTarget());
        assertFalse(summary.getCallGraph().getNodes().contains("g"),
                "文本目标不应混入调用图节点");
    }

    @Test
    @DisplayName("可变参数重载按任意实参个数命中")
    void varargsOverloadMatchesAnyArity() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void f() { log("x", 1); }
                    void log(String s) { }
                    void log(String fmt, Object... args) { }
                }
                """);
        MethodCall log = call(method(summary, "f"), "log");
        assertTrue(log.isResolved());
        assertEquals("t.A#log(String,Object)", log.getTarget());
    }

    @Test
    @DisplayName("无法定位接收者时目标退化为 scope.methodName 文本")
    void unknownScopeFallsBackToTextTarget() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void a() {
                        repo.findById(1);
                        System.out.println("x");
                    }
                }
                """);
        MethodInfo a = method(summary, "a");
        assertEquals("repo.findById", call(a, "findById").getTarget());
        assertFalse(call(a, "findById").isResolved());
        assertEquals("System.out.println", call(a, "println").getTarget());
        assertFalse(call(a, "println").isResolved());
        assertFalse(summary.getCallGraph().getNodes().contains("repo.findById"));
        assertFalse(summary.getCallGraph().getNodes().contains("System.out.println"));
    }

    @Test
    @DisplayName("lambda 体内的调用仍属于外层方法")
    void callsInsideLambdaCountForEnclosingMethod() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void a(java.util.List<String> xs) {
                        xs.forEach(x -> helper(x));
                    }
                    void helper(Object o) { }
                }
                """);
        MethodInfo a = method(summary, "a");
        assertEquals(2, a.getCalls().size(), "forEach 与 lambda 内的 helper 都应计入 a");
        MethodCall helper = call(a, "helper");
        assertTrue(helper.isResolved());
        assertEquals("t.A#helper(Object)", helper.getTarget());
        MethodCall forEach = call(a, "forEach");
        assertFalse(forEach.isResolved());
        assertEquals("xs.forEach", forEach.getTarget());
    }

    @Test
    @DisplayName("局部类体内的调用不计入外层方法，也不回退到外部类")
    void localClassBodyIsSeparateScope() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void a() {
                        class L {
                            void m() { b(); }
                        }
                    }
                    void b() { }
                }
                """);
        MethodInfo a = method(summary, "a");
        assertTrue(a.getCalls().isEmpty(), "局部类内的调用不应计入外层方法");

        MethodInfo m = method(summary, "m");
        assertEquals("t.A.L", m.getClassQualifiedName());
        MethodCall b = call(m, "b");
        assertFalse(b.isResolved(), "局部类方法内不向外部类回退查找");
        assertEquals("b", b.getTarget());
    }

    @Test
    @DisplayName("调用图节点覆盖全部方法签名（含构造函数）")
    void graphNodesCoverAllMethods() {
        AstSummary summary = parse("""
                package t;
                class A {
                    A() { }
                    void a() { b(); }
                    void b() { }
                }
                """);
        Set<String> signatures = summary.getMethods().stream()
                .map(MethodInfo::signature)
                .collect(Collectors.toSet());
        assertEquals(signatures, new HashSet<>(summary.getCallGraph().getNodes()));
        assertTrue(signatures.contains("t.A#A()"));
    }

    // ==================== 容错与统计 ====================

    @Test
    @DisplayName("语法错误的源码记录 parseErrors，不抛异常且摘要为空")
    void parseErrorsAreRecordedWithoutException() {
        AstSummary summary = PARSER.parse("class A { void f( { } }");
        assertFalse(summary.isParsed());
        assertFalse(summary.getParseErrors().isEmpty(), "语法错误应被记录");
        // JavaParser 对无法恢复的源码给出空 CU，而不是抛异常中断整个扫描
        assertTrue(summary.getMethods().isEmpty());
        assertTrue(summary.getClasses().isEmpty());
        assertEquals(0, summary.getMethodCount());
    }

    @Test
    @DisplayName("空源码视为解析成功但无内容")
    void emptySourceParsesAsEmptySummary() {
        AstSummary summary = PARSER.parse("");
        assertTrue(summary.isParsed());
        assertEquals(0, summary.getMethodCount());
        assertEquals(0, summary.getMaxComplexity());
        assertEquals(0.0, summary.getAvgComplexity());
    }

    @Test
    @DisplayName("派生统计随方法复杂度同步维护")
    void derivedStatsAreMaintained() {
        AstSummary summary = parse("""
                package t;
                class A {
                    void simple() { }
                    void two(int a) { if (a > 0) { } }
                    void five(int a) {
                        if (a > 0) { }
                        if (a > 1) { }
                        if (a > 2) { }
                        if (a > 3) { }
                    }
                }
                """);
        assertEquals(3, summary.getMethodCount());
        assertEquals(5, summary.getMaxComplexity());
        assertEquals(2.67, summary.getAvgComplexity(), 1e-9, "(1+2+5)/3 四舍五入到两位小数");
    }

    // ==================== 文件与目录 ====================

    @Test
    @DisplayName("parseFile 解析单个文件并保留路径")
    void parseFileKeepsPath(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "package t;\nclass Sample {\n    void f() { }\n}\n");
        AstSummary summary = PARSER.parseFile(file.toFile());
        assertEquals(file.toString(), summary.getFilePath());
        assertEquals("t", summary.getPackageName());
        assertEquals(1, summary.getMethodCount());
        assertEquals(file.toString(), PARSER.parseFile(file.toString()).getFilePath());
    }

    @Test
    @DisplayName("parseDirectory 递归解析 .java，忽略其它文件，结果按路径排序")
    void parseDirectoryWalksRecursively(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("A.java"), "package t;\nclass A { }");
        Files.writeString(tempDir.resolve("B.java"), "package t;\nclass B { }");
        Files.writeString(tempDir.resolve("sub/C.java"), "package t;\nclass C { }");
        Files.writeString(tempDir.resolve("notes.txt"), "不是 Java 文件");

        List<AstSummary> summaries = PARSER.parseDirectory(tempDir.toString());
        assertEquals(3, summaries.size());
        assertEquals(List.of("A.java", "B.java", "C.java"),
                summaries.stream()
                        .map(summary -> Path.of(summary.getFilePath()).getFileName().toString())
                        .toList());
    }
}
