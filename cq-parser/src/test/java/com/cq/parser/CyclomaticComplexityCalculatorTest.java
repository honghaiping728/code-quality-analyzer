package com.cq.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 圈复杂度计算器测试
 * <p>
 * 逐个验证判定点的计分规则，并覆盖三类容易算错的作用域：
 * lambda 计入外层方法、局部类与匿名类不计入（它们是独立作用域）。
 * 这里刻意绕开 {@link AstParserService} 直接把方法体交给计算器 ——
 * 圈复杂度是数量最多的一类规范规则（STYLE.COMPLEXITY_TOO_HIGH）的判定依据，
 * 与解析链路的其它部分解耦后，出问题时能直接定位到计分本身。
 */
class CyclomaticComplexityCalculatorTest {

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(LanguageLevel.JAVA_21));

    /** 解析一个方法声明并计算其方法体的圈复杂度 */
    private static int cc(String methodSource) {
        CompilationUnit cu = JAVA_PARSER
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider("class T { " + methodSource + " }"))
                .getResult()
                .orElseThrow(() -> new AssertionError("测试方法无法解析: " + methodSource));
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        return CyclomaticComplexityCalculator.calculate(method.getBody().orElseThrow());
    }

    // ==================== 基线 ====================

    @Test
    @DisplayName("空方法体复杂度为 1")
    void emptyBodyIsOne() {
        assertEquals(1, cc("void f() { }"));
    }

    @Test
    @DisplayName("传入 null 返回 1 而不是抛异常")
    void nullNodeReturnsOne() {
        assertEquals(1, CyclomaticComplexityCalculator.calculate(null));
    }

    // ==================== 分支与循环 ====================

    @Test
    @DisplayName("if 计 1，if/else 仍只计 1")
    void ifAddsOne() {
        assertEquals(2, cc("void f(int a) { if (a > 0) { } }"));
        assertEquals(2, cc("void f(int a) { if (a > 0) { } else { } }"));
    }

    @Test
    @DisplayName("else if 阶梯每个分支各计 1（AST 中是嵌套 if）")
    void elseIfLadderCountsEachBranch() {
        assertEquals(4, cc("""
                void f(int a) {
                    if (a == 1) { }
                    else if (a == 2) { }
                    else if (a == 3) { }
                }
                """));
    }

    @Test
    @DisplayName("嵌套 if 逐层累加")
    void nestedIfCountsBoth() {
        assertEquals(3, cc("void f(int a) { if (a > 0) { if (a > 1) { } } }"));
    }

    @Test
    @DisplayName("四种循环各计 1")
    void loopsAddOne() {
        assertEquals(2, cc("void f() { for (int i = 0; i < 3; i++) { } }"));
        assertEquals(2, cc("void f(java.util.List<String> xs) { for (String x : xs) { } }"));
        assertEquals(2, cc("void f(int a) { while (a > 0) { } }"));
        assertEquals(2, cc("void f(int a) { do { } while (a > 0); }"));
    }

    @Test
    @DisplayName("嵌套循环逐层累加")
    void nestedLoopsAccumulate() {
        assertEquals(3, cc("void f(java.util.List<String> xs) { for (String x : xs) { while (x != null) { } } }"));
    }

    // ==================== 异常与 switch ====================

    @Test
    @DisplayName("每个 catch 计 1，finally 不计")
    void catchAddsOne() {
        assertEquals(2, cc("void f() { try { } catch (Exception e) { } }"));
        assertEquals(2, cc("void f() { try { } catch (Exception e) { } finally { } }"));
        assertEquals(3, cc("""
                void f() {
                    try { }
                    catch (IllegalStateException e) { }
                    catch (IllegalArgumentException e) { }
                }
                """));
    }

    @Test
    @DisplayName("try-with-resources 没有 catch 时不计分")
    void tryWithResourcesAddsNothing() {
        assertEquals(1, cc("void f() throws Exception { try (java.io.InputStream in = null) { } }"));
    }

    @Test
    @DisplayName("switch 每个 case 标签计 1，default 不计")
    void switchCountsCaseLabels() {
        assertEquals(3, cc("""
                void f(int x) {
                    switch (x) {
                        case 1: break;
                        case 2: break;
                        default: break;
                    }
                }
                """));
    }

    @Test
    @DisplayName("多标签箭头 case 按标签个数计分")
    void arrowCaseCountsEachLabel() {
        assertEquals(3, cc("""
                void f(int x) {
                    switch (x) {
                        case 1, 2 -> { }
                        default -> { }
                    }
                }
                """));
    }

    // ==================== 表达式 ====================

    @Test
    @DisplayName("三元表达式计 1")
    void ternaryAddsOne() {
        assertEquals(2, cc("int f(int a) { return a > 0 ? 1 : 2; }"));
    }

    @Test
    @DisplayName("&& 与 || 各计 1，位运算不计")
    void logicalOperatorsAddOne() {
        assertEquals(2, cc("boolean f(boolean a, boolean b) { return a && b; }"));
        assertEquals(3, cc("boolean f(boolean a, boolean b, boolean c) { return a && b || c; }"));
        assertEquals(1, cc("boolean f(int a, int b) { return (a & b) > 0; }"),
                "按位与不是判定点");
    }

    // ==================== 作用域 ====================

    @Test
    @DisplayName("lambda 体内的判定点计入外层方法，lambda 自身不计")
    void lambdaBodyCountsForEnclosingMethod() {
        assertEquals(1, cc("void f(java.util.List<String> xs) { xs.forEach(x -> { }); }"));
        assertEquals(2, cc("void f(java.util.List<String> xs) { xs.forEach(x -> { if (x != null) { } }); }"));
    }

    @Test
    @DisplayName("局部类体不计入外层方法")
    void localClassBodyIsExcluded() {
        assertEquals(1, cc("""
                void f(int a) {
                    class Local {
                        void m(int b) {
                            if (b > 0) { }
                            if (b > 1) { }
                        }
                    }
                }
                """));
    }

    @Test
    @DisplayName("匿名类体不计入外层方法")
    void anonymousClassBodyIsExcluded() {
        assertEquals(1, cc("""
                void f() {
                    Runnable r = new Runnable() {
                        public void run() { if (true) { } }
                    };
                }
                """));
    }

    @Test
    @DisplayName("匿名类的构造实参仍计入外层方法")
    void anonymousClassArgumentsStillCount() {
        // new X(判定表达式) { ... } 中的实参由外层方法求值，必须保留
        assertEquals(3, cc("void f(boolean a, boolean b) { Object o = new java.util.ArrayList<>(a && b ? 1 : 0) { }; }"));
    }

    // ==================== 组合 ====================

    @Test
    @DisplayName("综合方法按各项累加")
    void combinedMethodAddsUp() {
        assertEquals(7, cc("""
                void f(java.util.List<String> xs, int a) {
                    for (String x : xs) {              // +1
                        if (x == null || a > 0) { }    // +1 (if) +1 (||)
                    }
                    try { } catch (Exception e) { }    // +1
                    int r = a > 0 ? 1 : 2;             // +1
                    switch (a) {                       // +1 (case 1)
                        case 1: break;
                        default: break;
                    }
                }
                """));
    }
}
