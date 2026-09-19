package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 扫描任务的文件明细
 */
public class ScanFile implements Serializable {

    private Long id;
    private Long taskId;
    private String filePath;
    private int lineCount;
    private int issueCount;
    private boolean parsed = true;
    private String parseError;
    private Date createTime;

    public ScanFile() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }

    public int getIssueCount() { return issueCount; }
    public void setIssueCount(int issueCount) { this.issueCount = issueCount; }

    public boolean isParsed() { return parsed; }
    public void setParsed(boolean parsed) { this.parsed = parsed; }

    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "ScanFile{" + filePath + ", lines=" + lineCount + ", issues=" + issueCount + "}";
    }
}
