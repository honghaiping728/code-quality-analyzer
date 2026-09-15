package com.cq.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * 圈复杂度（Cyclomatic Complexity，McCabe）计算器
 * <p>
 * 以方法体为统计单元，基础值为 1，在此基础上累加判定点：
 * <ul>
 *     <li>{@code if} / {@code else if}（else if 会被 AST 表示为嵌套 if，天然各计一次）</li>
 *     <li>{@code for} / {@code for-each} / {@code while} / {@code do-while}</li>
 *     <li>{@code catch} 分支</li>
 *     <li>{@code switch} 的每个 case 标签（{@code default} 不计，{@code case A, B ->} 计 2）</li>
 *     <li>三元表达式 {@code ? :}</li>
 *     <li>{@code &&} 与 {@code ||} 短路运算符</li>
 * </ul>
 * <p>
 * 作用域规则：Lambda 体归属于外层方法（其执行由外层方法控制）；而方法内声明的局部类、
 * 匿名类以及枚举常量体属于独立的方法作用域，不计入外层方法的复杂度（其自身方法会被
 * 单独解析为 {@code MethodInfo}）。
 */
public final class CyclomaticComplexityCalculator {

    private CyclomaticComplexityCalculator() {}

    /**
     * 计算指定节点的圈复杂度
     * @param node 方法的方法体（BlockStmt）或方法声明节点
     * @return 圈复杂度，最小为 1
     */
    public static int calculate(Node node) {
        if (node == null) {
            return 1;
        }
        ComplexityVisitor visitor = new ComplexityVisitor();
        node.accept(visitor, null);
        return visitor.complexity;
    }

    /**
     * 仅统计判定点，不访问子树
     */
    private static final class ComplexityVisitor extends VoidVisitorAdapter<Void> {

        private int complexity = 1;

        @Override
        public void visit(IfStmt n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForEachStmt n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(DoStmt n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(CatchClause n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ConditionalExpr n, Void arg) {
            complexity++;
            super.visit(n, arg);
        }

        @Override
        public void visit(BinaryExpr n, Void arg) {
            BinaryExpr.Operator operator = n.getOperator();
            if (operator == BinaryExpr.Operator.AND || operator == BinaryExpr.Operator.OR) {
                complexity++;
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(SwitchEntry n, Void arg) {
            // default 分支的 labels 为空，不计入复杂度
            complexity += n.getLabels().size();
            super.visit(n, arg);
        }

        /** 方法内的局部类/内部类属于独立作用域，不递归 */
        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        }

        @Override
        public void visit(EnumDeclaration n, Void arg) {
        }

        @Override
        public void visit(RecordDeclaration n, Void arg) {
        }

        @Override
        public void visit(AnnotationDeclaration n, Void arg) {
        }

        /** 匿名类体属于独立作用域，仅统计创建表达式自身的参数与作用域 */
        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            if (n.getAnonymousClassBody().isPresent()) {
                n.getArguments().forEach(argument -> argument.accept(this, arg));
                n.getScope().ifPresent(scope -> scope.accept(this, arg));
            } else {
                super.visit(n, arg);
            }
        }
    }
}
