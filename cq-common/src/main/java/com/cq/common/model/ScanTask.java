package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 扫描任务
 * <p>
 * 状态机：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED
 */
public class ScanTask implements Serializable {

    private Long id;
    private Long repoId;            // 所属仓库，本地目录扫描时为 null
    private String targetPath;      // 扫描目标：仓库本地路径或目录
    private String mode;            // FULL | INCREMENTAL
    private String baseCommit;      // 增量扫描的基线提交
    private String headCommit;      // 增量扫描的目标提交
    private String triggerType;     // MANUAL | WEBHOOK | SCHEDULE
    private String status;          // PENDING | RUNNING | SUCCESS | FAILED | CANCELLED
    private int fileCount;          // 扫描文件数
    private int issueCount;         // 发现问题数
    private Date startTime;
    private Date endTime;
    private Long durationMs;        // 耗时（毫秒）
    private String errorMessage;
    private Date createTime;

    public ScanTask() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getBaseCommit() { return baseCommit; }
    public void setBaseCommit(String baseCommit) { this.baseCommit = baseCommit; }

    public String getHeadCommit() { return headCommit; }
    public void setHeadCommit(String headCommit) { this.headCommit = headCommit; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public int getIssueCount() { return issueCount; }
    public void setIssueCount(int issueCount) { this.issueCount = issueCount; }

    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }

    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "ScanTask{id=" + id + ", path='" + targetPath + "', status=" + status
                + ", files=" + fileCount + ", issues=" + issueCount + ", cost=" + durationMs + "ms}";
    }
}
