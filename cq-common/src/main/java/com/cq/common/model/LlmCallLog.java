package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * LLM 调用日志
 * <p>
 * 记录每次调用的 token 消耗与耗时，让大模型成本可观测 —— 这是 README 中
 * 「增量扫描降低调用成本」能否被验证的前提。
 */
public class LlmCallLog implements Serializable {

    private Long id;
    private Long taskId;
    private Long issueId;
    private String scene;            // CONFIRM 语义确认 | SUGGEST 修复建议
    private String model;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private Long durationMs;
    private boolean success = true;
    private String errorMessage;
    private Date createTime;

    public LlmCallLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    public Long getIssueId() { return issueId; }
    public void setIssueId(Long issueId) { this.issueId = issueId; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "LlmCallLog{scene=" + scene + ", tokens=" + totalTokens + ", cost=" + durationMs + "ms}";
    }
}
