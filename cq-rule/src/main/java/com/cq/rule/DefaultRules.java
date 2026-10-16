package com.cq.rule;

import com.cq.rule.bug.BugRules;
import com.cq.rule.performance.PerformanceRules;
import com.cq.rule.security.SecurityRules;
import com.cq.rule.style.StyleRules;
import com.cq.rule.text.TextBugRules;
import com.cq.rule.text.TextPerformanceRules;
import com.cq.rule.text.TextSecurityRules;
import com.cq.rule.text.TextStyleRules;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置规则集
 * <p>
 * 共 57 条，四维分布 14 / 15 / 13 / 15，满足「Bug/安全/性能/规范各 10 条以上」的要求。
 * 其中 42 条建立在 JavaParser 的 AST 之上（只作用于 {@code .java}），
 * 另 15 条是面向非 Java 文件的文本规则，详见 {@code com.cq.rule.text}。
 * <p>
 * 注册方式刻意选编译期列表而非 ServiceLoader：规则与引擎同 jar 发布，
 * ServiceLoader 只会在资源文件缺失时静默失效，多一种失败模式而无收益。
 */
public final class DefaultRules {

    private DefaultRules() {}

    /**
     * 全部内置规则
     * @return 规则列表
     */
    public static List<Rule> all() {
        List<Rule> rules = new ArrayList<>();
        rules.addAll(BugRules.all());
        rules.addAll(SecurityRules.all());
        rules.addAll(PerformanceRules.all());
        rules.addAll(StyleRules.all());
        rules.addAll(TextBugRules.all());
        rules.addAll(TextSecurityRules.all());
        rules.addAll(TextPerformanceRules.all());
        rules.addAll(TextStyleRules.all());
        return List.copyOf(rules);
    }
}
