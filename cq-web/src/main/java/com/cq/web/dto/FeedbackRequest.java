package com.cq.web.dto;

/**
 * 提交采纳/误报反馈的请求体
 */
public class FeedbackRequest {

    private Long issueId;
    private Long suggestionId;
    private String ruleId;
    private String action;    // ACCEPT | REJECT | FALSE_POSITIVE
    private String comment;

    public Long getIssueId() { return issueId; }
    public void setIssueId(Long issueId) { this.issueId = issueId; }

    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long suggestionId) { this.suggestionId = suggestionId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
