package com.cq.common.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次扫描用到的完整规则配置
 * <p>
 * 解析优先级：规则级覆盖（{@link RuleConfig}）→ 全局阈值（{@link RuleThresholds}）→ 硬编码默认值。
 * 规则实现通过 {@code RuleContext} 读取，不直接接触本结构。
 */
public class RuleConfigSet implements Serializable {

    private RuleThresholds thresholds = new RuleThresholds();
    private Map<String, RuleConfig> rules = new HashMap<>();       // 按 ruleId 索引
    private List<IgnoreEntry> ignoreEntries = new ArrayList<>();   // 忽略列表

    public RuleConfigSet() {}

    /** 是否启用指定规则；无配置行时默认启用 */
    public boolean isEnabled(String ruleId) {
        RuleConfig config = rules.get(ruleId);
        return config == null || config.isEnabled();
    }

    /** 追加忽略项 */
    public void addIgnoreEntry(IgnoreEntry entry) {
        ignoreEntries.add(entry);
    }

    public RuleThresholds getThresholds() { return thresholds; }
    public void setThresholds(RuleThresholds thresholds) { this.thresholds = thresholds; }

    public Map<String, RuleConfig> getRules() { return rules; }
    public void setRules(Map<String, RuleConfig> rules) { this.rules = rules; }

    public List<IgnoreEntry> getIgnoreEntries() { return ignoreEntries; }
    public void setIgnoreEntries(List<IgnoreEntry> ignoreEntries) { this.ignoreEntries = ignoreEntries; }
}
