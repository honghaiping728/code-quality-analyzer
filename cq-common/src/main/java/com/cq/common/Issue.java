package com.cq.common;

import java.io.Serializable;
import java.util.Date;

/**
 * 统一问题对象 Schema
 * 供规则引擎、Code Agent、报告模块共用
 */
public class Issue implements Serializable {

    private Long id;
    private Long taskId;        // 所属扫描任务
    private Long repoId;
    private String commitId;
    private String ruleId;      // 规则 ID，如 BUG.EMPTY_CATCH
    private String filePath;
    private int line;
    private String lineHash;    // 问题所在源码行的 SHA-256，用于跨版本稳定匹配
    private String type;        // BUG | SECURITY | PERFORMANCE | STYLE
    private String severity;    // BLOCKER | CRITICAL | MAJOR | MINOR
    private String source;      // RULE | LLM | FUSED
    private double confidence;  // 0.0 - 1.0
    private String message;     // 问题描述
    private String cause;       // 成因解释
    private String suggestion;  // 修复建议
    private String diff;        // 修复代码 Diff
    private String codeSnippet; // 问题所在代码片段
    private String status;      // OPEN | CONFIRMED | FALSE_POSITIVE | FIXED
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCause() { return cause; }
    public void setCause(String cause) { this.cause = cause; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public String getLineHash() { return lineHash; }
    public void setLineHash(String lineHash) { this.lineHash = lineHash; }

    public String getDiff() { return diff; }
    public void setDiff(String diff) { this.diff = diff; }

    public String getCodeSnippet() { return codeSnippet; }
    public void setCodeSnippet(String codeSnippet) { this.codeSnippet = codeSnippet; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
