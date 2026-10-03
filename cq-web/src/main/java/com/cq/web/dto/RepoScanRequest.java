package com.cq.web.dto;

/**
 * GitHub 仓库在线扫描的请求体
 */
public class RepoScanRequest {

    private String url;         // 仓库地址（https 或 git@ 形式）
    private String branch;      // 分支，可为空（使用仓库默认分支）
    private String mode = "FULL";   // FULL 全量 | INCREMENTAL 增量
    private String baseCommit;  // 增量基线提交，可为空（默认上次扫描的提交）
    private String headCommit;  // 增量目标提交，可为空（默认分支最新提交）

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getBaseCommit() { return baseCommit; }
    public void setBaseCommit(String baseCommit) { this.baseCommit = baseCommit; }

    public String getHeadCommit() { return headCommit; }
    public void setHeadCommit(String headCommit) { this.headCommit = headCommit; }
}
