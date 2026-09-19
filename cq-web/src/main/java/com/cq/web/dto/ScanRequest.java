package com.cq.web.dto;

/**
 * 启动扫描的请求体
 */
public class ScanRequest {

    private String path;        // 扫描目标目录或单个文件
    private String mode = "FULL";   // FULL | INCREMENTAL
    private Long repoId;        // 关联仓库，可为空

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
}
