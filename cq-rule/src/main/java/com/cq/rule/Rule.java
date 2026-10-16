package com.cq.rule;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.lang.Language;

import java.util.List;
import java.util.Set;

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
     * 本规则适用的语言
     * <p>
     * 默认只作用于 Java：既有 42 条规则全部建立在 JavaParser 的 AST 之上，
     * 其判据（{@code MethodDeclaration}、{@code TryStmt}……）在别的语言里没有对应物。
     * 基于行扫描的规则则应显式声明范围 —— 与语言无关的问题（行尾空白、遗留的合并
     * 冲突标记、明文私钥）用 {@link Language#scannable()} 表示「全部语言」，
     * 其余按需列举。
     * <p>
     * 引擎在检查前按此过滤，因此某条规则即便写错了也不会在不适用的语言上执行。
     * @return 适用语言集合，不可为 null
     */
    default Set<Language> languages() {
        return Set.of(Language.JAVA);
    }

    /**
     * 执行检查
     * @param ctx 规则上下文
     * @return 命中的问题列表，无命中时返回空列表（不返回 null）
     */
    List<Issue> check(RuleContext ctx);
}
