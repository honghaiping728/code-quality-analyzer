package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 代码仓库
 */
public class Repo implements Serializable {

    private Long id;
    private String name;
    private String url;
    private String defaultBranch = "main";
    private String localPath;
    private String username;
    private String credential;      // 拉取凭证，加密存储
    private String lastCommitId;    // 上次扫描到的提交，增量扫描基线
    private Date lastScanTime;
    private boolean enabled = true;
    private Date createTime;

    public Repo() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }

    public String getLastCommitId() { return lastCommitId; }
    public void setLastCommitId(String lastCommitId) { this.lastCommitId = lastCommitId; }

    public Date getLastScanTime() { return lastScanTime; }
    public void setLastScanTime(Date lastScanTime) { this.lastScanTime = lastScanTime; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "Repo{id=" + id + ", name='" + name + "', url='" + url + "'}";
    }
}
