package com.cq.common.model;

import java.io.Serializable;

/**
 * 单条规则的配置
 * <p>
 * {@link #severity} / {@link #confidence} 为 null 时表示沿用规则自身的默认值，
 * 这样新增规则无需同步写配置行。
 */
public class RuleConfig implements Serializable {

    private Long id;                // 数据库主键；规则身份由 ruleId 唯一决定
    private String ruleId;          // 规则 ID，如 STYLE.METHOD_TOO_LONG
    private String ruleName;
    private String category;        // BUG | SECURITY | PERFORMANCE | STYLE
    private boolean enabled = true;
    private String severity;        // 严重级覆盖，null 表示用规则默认值
    private Double confidence;      // 置信度覆盖，null 表示用规则默认值
    private String description;

    public RuleConfig() {}

    public RuleConfig(String ruleId, String ruleName, String category) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.category = category;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return "RuleConfig{" + ruleId + ", enabled=" + enabled
                + ", severity=" + severity + ", confidence=" + confidence + "}";
    }
}
