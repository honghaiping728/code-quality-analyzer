package com.cq.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.Optional;

/**
 * AST 作用域判断工具
 * <p>
 * 从 {@link AstParserService} 中提取，供规则引擎复用。核心是回答两个问题：
 * <ol>
 *     <li>「这个调用点属于哪个方法？」—— 需要跳过嵌套的局部类、匿名类、枚举常量体</li>
 *     <li>「这个调用点是立即执行还是延迟执行？」—— lambda 与匿名类体内的调用并非
 *         每次迭代都执行，循环类性能规则必须排除，否则会把
 *         {@code list.forEach(x -> dao.find(x))} 这类延迟执行的调用误判为「循环内查库」</li>
 * </ol>
 */
public final class AstScopeUtils {

    private AstScopeUtils() {}

    /**
     * 判断节点是否直接位于指定可调用体（方法/构造函数）内
     * <p>
     * 途中若穿过类型声明、匿名类体或枚举常量体，则认为属于嵌套作用域，返回 false
     * （那些作用域里的方法会被单独解析为各自的 MethodInfo）。
     * @param node 待判断节点
     * @param owner 目标可调用体
     * @return 是否直接位于该可调用体内
     */
    public static boolean belongsToCallable(Node node, CallableDeclaration<?> owner) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != owner) {
            if (isScopeBoundary(current)) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return current == owner;
    }

    /**
     * 沿父链查找最近的可调用体（方法/构造函数），跳过 lambda 继续向外找
     * @param node 起始节点
     * @return 最近的可调用体，找不到时为空
     */
    public static Optional<CallableDeclaration<?>> enclosingCallable(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof CallableDeclaration<?> callable) {
                return Optional.of(callable);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * 判断节点是否处于「延迟执行」作用域内
     * <p>
     * 即节点与最近的循环体之间隔着 lambda 或匿名类。这类调用不是在每轮迭代中立即执行的，
     * 循环内的性能规则应当排除。
     * @param node 待判断节点
     * @param loopBody 循环体节点
     * @return 是否被延迟作用域隔开
     */
    public static boolean isDeferredWithin(Node node, Node loopBody) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != loopBody) {
            if (current instanceof LambdaExpr
                    || (current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent())) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * 是否为作用域边界（类型声明 / 匿名类体 / 枚举常量体）
     * @param node 节点
     * @return 是否为边界
     */
    public static boolean isScopeBoundary(Node node) {
        return node instanceof TypeDeclaration
                || (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent())
                || (node instanceof EnumConstantDeclaration constant && !constant.getClassBody().isEmpty());
    }

    /**
     * 节点所在的行号，取不到时返回 -1
     * @param node 节点
     * @return 起始行号
     */
    public static int lineOf(Node node) {
        return node.getBegin().map(position -> position.line).orElse(-1);
    }

    /**
     * 沿父链查找最近的循环语句
     * <p>
     * 途中遇到类型声明或匿名类体即停止并返回空 —— 那些是独立作用域，
     * 其中的语句不属于外层循环的迭代体。
     * @param node 起始节点
     * @return 最近的循环节点，不在循环内时为空
     */
    public static Optional<Node> enclosingLoop(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ForStmt || current instanceof ForEachStmt
                    || current instanceof WhileStmt || current instanceof DoStmt) {
                return Optional.of(current);
            }
            if (isScopeBoundary(current)) {
                return Optional.empty();
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
