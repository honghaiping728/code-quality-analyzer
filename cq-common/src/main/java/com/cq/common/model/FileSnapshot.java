package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 文件快照
 * <p>
 * 记录某次提交下文件的路径与内容哈希，用于增量扫描时判断「哪些文件真的变了」，
 * 以及跨版本的趋势分析。
 */
public class FileSnapshot implements Serializable {

    private Long id;
    private Long repoId;
    private String commitId;
    private String filePath;
    private String contentHash;   // 文件内容 SHA-256
    private int lineCount;
    private Date createTime;

    public FileSnapshot() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "FileSnapshot{" + commitId + ":" + filePath + "}";
    }
}
