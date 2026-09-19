package com.cq.rule;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;

import java.util.List;

/**
 * 代码检查规则
 * <p>
 * 实现类必须**无状态且线程安全**（与 {@code AstParserService} 的约定一致），
 * 任何逐文件的可变状态都应放在 {@link RuleContext} 中。
 * <p>
 * {@link #id()} 是稳定且带命名空间的字符串（如 {@code BUG.EMPTY_CATCH}），
 * 它同时是规则启停配置、误报抑制、问题去重与报告聚合的关联键，一旦发布不应再变。
 * <p>
 * {@link #severity()} 与 {@link #confidence()} 是**默认值**，单条命中可通过
 * {@link RuleContext} 的构造辅助方法覆盖（例如 catch Exception 比 catch IOException 更严重）。
 */
public interface Rule {

    /** 规则唯一 ID，形如 {@code BUG.EMPTY_CATCH} */
    String id();

    /** 规则中文名 */
    String name();

    /** 所属分析维度 */
    IssueType type();

    /** 默认严重级 */
    Severity severity();

    /** 默认置信度（0.0 - 1.0） */
    double confidence();

    /** 规则说明，用于规则配置页展示 */
    String description();

    /**
     * 执行检查
     * @param ctx 规则上下文
     * @return 命中的问题列表，无命中时返回空列表（不返回 null）
     */
    List<Issue> check(RuleContext ctx);
}
