package com.cq.common;

import java.io.Serializable;
import java.util.Date;

/**
 * 统一问题对象 Schema
 * 供规则引擎、Code Agent、报告模块共用
 */
public class Issue implements Serializable {

    private Long id;
    private Long repoId;
    private String commitId;
    private String file;
    private int line;
    private String type;        // BUG | SECURITY | PERFORMANCE | STYLE
    private String severity;    // BLOCKER | CRITICAL | MAJOR | MINOR
    private String source;      // RULE | LLM | FUSED
    private double confidence;  // 0.0 - 1.0
    private String message;     // 问题描述
    private String cause;       // 成因解释
    private String suggestion;  // 修复建议
    private String diff;        // 修复代码 Diff
    private String status;      // OPEN | CONFIRMED | FALSE_POSITIVE | FIXED
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

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

    public String getDiff() { return diff; }
    public void setDiff(String diff) { this.diff = diff; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
