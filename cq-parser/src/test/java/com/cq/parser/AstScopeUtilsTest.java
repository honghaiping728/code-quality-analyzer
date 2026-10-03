package com.cq.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST 作用域工具测试
 * <p>
 * 工具回答两个问题：调用点属于哪个方法（{@link AstScopeUtils#belongsToCallable}）、
 * 调用是立即执行还是延迟执行（{@link AstScopeUtils#isDeferredWithin}）。
 * 前者决定调用图的边归属，后者直接决定循环类性能规则是否把
 * {@code list.forEach(x -> dao.find(x))} 误判为「循环内查库」。
 */
class AstScopeUtilsTest {

    /** 一份含各种嵌套作用域的样本：lambda、局部类、匿名类、循环、枚举常量体 */
    private static final String SOURCE = """
            package t;
            class T {
                void outer() {
                    int alpha = 1;
                    Runnable lambda = () -> { int beta = 2; };
                    class Local {
                        void inner() { int gamma = 3; }
                    }
                    Runnable anon = new Runnable() {
                        public void run() { int delta = 4; }
                    };
                    Object plain = new Object();
                    for (int i = 0; i < 3; i++) {
                        int epsilon = 5;
                        Runnable inLoopLambda = () -> { int zeta = 6; };
                        while (i > 0) {
                            int eta = 7;
                        }
                        Runnable anonInLoop = new Runnable() {
                            public void run() { int theta = 8; }
                        };
                    }
                    helper();
                }
                void helper() { }
                enum E {
                    A {
                        void m() { int iota = 9; }
                    },
                    B;
                }
            }
            """;

    private static final CompilationUnit CU = StaticJavaParser.parse(SOURCE);

    // ==================== 工具 ====================

    private static VariableDeclarator var(String name) {
        return CU.findAll(VariableDeclarator.class).stream()
                .filter(d -> d.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("源码中找不到变量 " + name));
    }

    private static MethodDeclaration methodNamed(String name) {
        return CU.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("源码中找不到方法 " + name));
    }

    /**
     * 取直接包含指定变量的方法
     * <p>
     * 含该变量的方法可能有多层祖先（外层方法也「包含」嵌套作用域里的变量），
     * 而 pre-order 的 findAll 里祖先排在后代之前，因此取最后一个匹配即最内层。
     */
    private static MethodDeclaration methodContaining(String variable) {
        return CU.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.findAll(VariableDeclarator.class).stream()
                        .anyMatch(d -> d.getNameAsString().equals(variable)))
                .reduce((outer, inner) -> inner)
                .orElseThrow(() -> new AssertionError("找不到包含变量 " + variable + " 的方法"));
    }

    // ==================== belongsToCallable ====================

    @Test
    @DisplayName("方法体节点属于该方法，lambda 不是作用域边界，局部类/匿名类/枚举常量体是")
    void belongsToCallableStopsAtScopeBoundaries() {
        MethodDeclaration outer = methodNamed("outer");
        assertTrue(AstScopeUtils.belongsToCallable(var("alpha"), outer));
        assertTrue(AstScopeUtils.belongsToCallable(var("beta"), outer),
                "lambda 体的执行由外层方法控制，不应视为独立作用域");
        assertFalse(AstScopeUtils.belongsToCallable(var("gamma"), outer), "局部类是独立作用域");
        assertFalse(AstScopeUtils.belongsToCallable(var("delta"), outer), "匿名类是独立作用域");
        assertFalse(AstScopeUtils.belongsToCallable(var("iota"), outer), "枚举常量体是独立作用域");
    }

    @Test
    @DisplayName("边界内节点归属于它自己的方法")
    void belongsToCallableIsTrueForOwnScope() {
        assertTrue(AstScopeUtils.belongsToCallable(var("gamma"), methodNamed("inner")));
        assertTrue(AstScopeUtils.belongsToCallable(var("delta"), methodContaining("delta")));
        assertFalse(AstScopeUtils.belongsToCallable(var("alpha"), methodNamed("inner")),
                "外层方法的变量不属于局部类方法");
    }

    // ==================== enclosingCallable ====================

    @Test
    @DisplayName("向上找最近的可调用体，遇到 lambda 继续向外")
    void enclosingCallableSkipsLambda() {
        assertSame(methodNamed("outer"), AstScopeUtils.enclosingCallable(var("alpha")).orElseThrow());
        assertSame(methodNamed("outer"), AstScopeUtils.enclosingCallable(var("beta")).orElseThrow());
        assertSame(methodNamed("inner"), AstScopeUtils.enclosingCallable(var("gamma")).orElseThrow());
        assertSame(methodContaining("delta"), AstScopeUtils.enclosingCallable(var("delta")).orElseThrow());
    }

    // ==================== enclosingLoop ====================

    @Test
    @DisplayName("沿父链找最近的循环，作用域边界中断查找")
    void enclosingLoopFindsNearestLoop() {
        ForStmt forStmt = CU.findFirst(ForStmt.class).orElseThrow();
        WhileStmt whileStmt = CU.findFirst(WhileStmt.class).orElseThrow();

        assertSame(forStmt, AstScopeUtils.enclosingLoop(var("epsilon")).orElseThrow());
        assertSame(whileStmt, AstScopeUtils.enclosingLoop(var("eta")).orElseThrow(), "应取最内层循环");
        assertSame(forStmt, AstScopeUtils.enclosingLoop(var("zeta")).orElseThrow(), "lambda 不中断循环查找");

        assertTrue(AstScopeUtils.enclosingLoop(var("alpha")).isEmpty(), "循环外应为空");
        assertTrue(AstScopeUtils.enclosingLoop(var("gamma")).isEmpty(), "局部类是独立作用域");
        assertTrue(AstScopeUtils.enclosingLoop(var("theta")).isEmpty(), "匿名类是独立作用域");
        assertTrue(AstScopeUtils.enclosingLoop(var("iota")).isEmpty(), "枚举常量体是独立作用域");
    }

    // ==================== isDeferredWithin ====================

    @Test
    @DisplayName("循环体内 lambda 与匿名类中的代码是延迟执行")
    void isDeferredWithinDetectsDeferredScopes() {
        Node forBody = CU.findFirst(ForStmt.class).orElseThrow().getBody();

        assertFalse(AstScopeUtils.isDeferredWithin(var("epsilon"), forBody), "循环体内的直接代码是立即执行");
        assertTrue(AstScopeUtils.isDeferredWithin(var("zeta"), forBody), "lambda 内是延迟执行");
        assertTrue(AstScopeUtils.isDeferredWithin(var("theta"), forBody), "匿名类内是延迟执行");
        assertFalse(AstScopeUtils.isDeferredWithin(var("alpha"), forBody), "不在该循环内");
    }

    // ==================== isScopeBoundary ====================

    @Test
    @DisplayName("只有带类体的匿名类与枚举常量才是边界")
    void isScopeBoundaryDistinguishesBodies() {
        assertTrue(AstScopeUtils.isScopeBoundary(CU.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow()));

        ObjectCreationExpr plain = CU.findAll(ObjectCreationExpr.class).stream()
                .filter(e -> e.getAnonymousClassBody().isEmpty())
                .findFirst()
                .orElseThrow();
        assertFalse(AstScopeUtils.isScopeBoundary(plain), "new Object() 没有匿名类体，不是边界");

        ObjectCreationExpr anonymous = CU.findAll(ObjectCreationExpr.class).stream()
                .filter(e -> e.getAnonymousClassBody().isPresent())
                .findFirst()
                .orElseThrow();
        assertTrue(AstScopeUtils.isScopeBoundary(anonymous));

        List<EnumConstantDeclaration> constants = CU.findAll(EnumConstantDeclaration.class);
        assertEquals(2, constants.size());
        assertTrue(AstScopeUtils.isScopeBoundary(constants.get(0)), "A 带类体");
        assertFalse(AstScopeUtils.isScopeBoundary(constants.get(1)), "B 无类体");
    }

    // ==================== lineOf ====================

    @Test
    @DisplayName("lineOf 取起始行，无位置信息的节点返回 -1")
    void lineOfReturnsStartLine() {
        int expected = 0;
        String[] lines = SOURCE.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("int alpha = 1;")) {
                expected = i + 1;
                break;
            }
        }
        assertEquals(expected, AstScopeUtils.lineOf(var("alpha")));
        assertEquals(-1, AstScopeUtils.lineOf(new NameExpr("x")));
    }
}
