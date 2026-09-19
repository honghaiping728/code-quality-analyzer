package com.cq.rule;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.parser.AstScopeUtils;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * 「循环内的某个调用」类规则基类
 * <p>
 * 性能维度里有六条规则都是这个形状（循环内查库、编译正则、sleep、创建日期格式化器等），
 * 循环发现与**延迟作用域排除**只在这里写一遍。
 * <p>
 * 延迟排除是这类规则最大的误报来源：{@code list.forEach(x -> dao.find(x))} 中的
 * {@code dao.find} 在语法上位于循环体内，但它是回调、不是每轮迭代都执行，
 * 与 {@code for (...) { dao.find(x); }} 有本质区别。{@link AstScopeUtils#isDeferredWithin}
 * 负责识别这种情况。
 */
public abstract class LoopCallRule extends AbstractRule {

    protected LoopCallRule(String id, String name, IssueType type,
                           Severity severity, double confidence, String description) {
        super(id, name, type, severity, confidence, description);
    }

    @Override
    public List<Issue> check(RuleContext ctx) {
        List<Issue> issues = new ArrayList<>();
        for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
            if (!matches(call, ctx)) {
                continue;
            }
            Node loop = enclosingLoop(call);
            if (loop == null || isInLoopHeader(call, loop) || AstScopeUtils.isDeferredWithin(call, loop)) {
                continue;
            }
            issues.add(issue(ctx, call, message(call, ctx), suggestion(call, ctx)));
        }
        return issues;
    }

    /**
     * 调用是否位于循环的「头部」而非循环体
     * <p>
     * {@code for (Row row : mapper.selectList(null))} 里的查询在语法上属于 ForEachStmt，
     * 但它作为被遍历的表达式**只执行一次**，不是每轮迭代都查库。不做这个区分会把
     * 「先一次性查出集合再遍历」这种完全正确的写法报成 N+1。
     * @param call 调用表达式
     * @param loop 循环节点
     * @return 是否位于循环头部
     */
    private static boolean isInLoopHeader(Node call, Node loop) {
        Node body = loopBodyOf(loop);
        return body == null || !body.isAncestorOf(call);
    }

    /** 取循环体的语句节点 */
    private static Node loopBodyOf(Node loop) {
        if (loop instanceof ForStmt forStmt) {
            return forStmt.getBody();
        }
        if (loop instanceof ForEachStmt forEachStmt) {
            return forEachStmt.getBody();
        }
        if (loop instanceof WhileStmt whileStmt) {
            return whileStmt.getBody();
        }
        if (loop instanceof DoStmt doStmt) {
            return doStmt.getBody();
        }
        return null;
    }

    /**
     * 判断该调用是否需要报告
     * @param call 调用表达式
     * @param ctx 上下文
     * @return 是否命中
     */
    protected abstract boolean matches(MethodCallExpr call, RuleContext ctx);

    /**
     * 问题描述
     * @param call 调用表达式
     * @param ctx 上下文
     * @return 描述文本
     */
    protected abstract String message(MethodCallExpr call, RuleContext ctx);

    /**
     * 修复建议
     * @param call 调用表达式
     * @param ctx 上下文
     * @return 建议文本
     */
    protected String suggestion(MethodCallExpr call, RuleContext ctx) {
        return null;
    }

    /**
     * 沿父链查找最近的循环语句
     * @param node 起始节点
     * @return 最近的循环节点，不在循环内时为 null
     */
    protected static Node enclosingLoop(Node node) {
        return AstScopeUtils.enclosingLoop(node).orElse(null);
    }
}
