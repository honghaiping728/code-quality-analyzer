package com.cq.rule;

import com.cq.common.Issue;
import com.cq.common.IssueSource;
import com.cq.common.IssueStatus;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.github.javaparser.ast.Node;

/**
 * 规则基类
 * <p>
 * 把「元数据 + 构造 Issue」这部分样板代码收敛到一处。子类只需实现
 * {@link #check(RuleContext)} 并调用 {@link #issue} 系列方法产出命中。
 */
public abstract class AbstractRule implements Rule {

    private final String id;
    private final String name;
    private final IssueType type;
    private final Severity severity;
    private final double confidence;
    private final String description;

    protected AbstractRule(String id, String name, IssueType type,
                           Severity severity, double confidence, String description) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.severity = severity;
        this.confidence = confidence;
        this.description = description;
    }

    @Override
    public String id() { return id; }

    @Override
    public String name() { return name; }

    @Override
    public IssueType type() { return type; }

    @Override
    public Severity severity() { return severity; }

    @Override
    public double confidence() { return confidence; }

    @Override
    public String description() { return description; }

    // ==================== 类型名比较 ====================

    /**
     * 类型文本是否匹配指定简单名
     * <p>
     * 必须经过归一化再比较，否则两类常见写法会漏判：
     * <ul>
     *     <li>全限定名：{@code javax.crypto.Cipher} 与 {@code Cipher}</li>
     *     <li>菱形语法：{@code new ArrayList<>(x)} 的 {@code getTypeAsString()} 是
     *         {@code "ArrayList<>"} 而非 {@code "ArrayList"}</li>
     * </ul>
     * @param typeText 类型文本或作用域文本
     * @param expected 期望的简单名，可多个
     * @return 是否匹配
     */
    protected static boolean typeNameIs(String typeText, String... expected) {
        String simple = LocalTypeIndex.rawName(typeText);
        if (simple == null) {
            return false;
        }
        for (String candidate : expected) {
            if (candidate.equals(simple)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 构造命中 ====================

    /**
     * 以规则默认严重级与置信度构造一条命中
     * @param ctx 上下文
     * @param node 命中的 AST 节点，用于取行号与代码片段
     * @param message 问题描述
     * @return 问题对象
     */
    protected Issue issue(RuleContext ctx, Node node, String message) {
        return issue(ctx, node, message, null, null, null);
    }

    /**
     * 构造一条命中
     * @param ctx 上下文
     * @param node 命中的 AST 节点
     * @param message 问题描述
     * @param suggestion 修复建议
     * @return 问题对象
     */
    protected Issue issue(RuleContext ctx, Node node, String message, String suggestion) {
        return issue(ctx, node, message, suggestion, null, null);
    }

    /**
     * 构造一条命中，可覆盖严重级与置信度
     * <p>
     * 单条命中覆盖是必要的：{@code catch (Exception)} 吞异常比 {@code catch (IOException)}
     * 严重得多，用同一个规则级严重级无法表达。
     * @param ctx 上下文
     * @param node 命中的 AST 节点
     * @param message 问题描述
     * @param suggestion 修复建议，可为 null
     * @param severityOverride 严重级覆盖，为 null 表示用规则默认值
     * @param confidenceOverride 置信度覆盖，为 null 表示用规则默认值
     * @return 问题对象
     */
    protected Issue issue(RuleContext ctx, Node node, String message, String suggestion,
                          Severity severityOverride, Double confidenceOverride) {
        int line = ctx.lineOf(node);
        Issue issue = new Issue();
        issue.setRuleId(id());
        issue.setFilePath(ctx.filePath());
        issue.setLine(line);
        issue.setLineHash(ctx.lineHash(line));
        issue.setType(type.name());
        issue.setSeverity((severityOverride == null ? ctx.severityOf(this) : severityOverride).name());
        issue.setSource(IssueSource.RULE.name());
        issue.setConfidence(confidenceOverride == null ? ctx.confidenceOf(this) : confidenceOverride);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        issue.setCodeSnippet(ctx.lineAt(line));
        issue.setStatus(IssueStatus.OPEN.name());
        return issue;
    }

    /**
     * 构造一条指定行号（而非节点位置）的命中
     * <p>
     * 用于「问题在循环内，但应报告在调用点」这类场景。
     * @param ctx 上下文
     * @param line 行号
     * @param message 问题描述
     * @param suggestion 修复建议
     * @return 问题对象
     */
    protected Issue issueAtLine(RuleContext ctx, int line, String message, String suggestion) {
        Issue issue = new Issue();
        issue.setRuleId(id());
        issue.setFilePath(ctx.filePath());
        issue.setLine(line);
        issue.setLineHash(ctx.lineHash(line));
        issue.setType(type.name());
        issue.setSeverity(ctx.severityOf(this).name());
        issue.setSource(IssueSource.RULE.name());
        issue.setConfidence(ctx.confidenceOf(this));
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        issue.setCodeSnippet(ctx.lineAt(line));
        issue.setStatus(IssueStatus.OPEN.name());
        return issue;
    }
}
