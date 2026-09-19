package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 修复建议的采纳反馈
 * <p>
 * 开发者「采纳 / 拒绝 / 标为误报」的行为回流，是阈值与规则配置调优的数据源
 * （对应 README 的「误报反馈自学习」）。
 */
public class SuggestionFeedback implements Serializable {

    private Long id;
    private Long suggestionId;
    private Long issueId;
    private String ruleId;      // 冗余，便于按规则聚合统计
    private String action;      // ACCEPT | REJECT | FALSE_POSITIVE
    private String comment;
    private Long userId;
    private Date createTime;

    public SuggestionFeedback() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long suggestionId) { this.suggestionId = suggestionId; }

    public Long getIssueId() { return issueId; }
    public void setIssueId(Long issueId) { this.issueId = issueId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "SuggestionFeedback{rule=" + ruleId + ", action=" + action + "}";
    }
}
