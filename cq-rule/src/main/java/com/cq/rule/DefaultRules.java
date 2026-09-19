package com.cq.rule;

import com.cq.rule.bug.BugRules;
import com.cq.rule.performance.PerformanceRules;
import com.cq.rule.security.SecurityRules;
import com.cq.rule.style.StyleRules;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置规则集
 * <p>
 * 共 42 条，四维分布 10 / 10 / 10 / 12，满足「Bug/安全/性能/规范各 10 条以上」的要求。
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
        return List.copyOf(rules);
    }
}
