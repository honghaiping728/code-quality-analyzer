package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 系统用户（cq-admin 权限管理）
 */
public class SysUser implements Serializable {

    private Long id;
    private String username;
    private String password;    // 加盐哈希，禁止明文
    private String displayName;
    private String role = "USER";   // ADMIN | USER
    private boolean enabled = true;
    private Date createTime;
    private Date updateTime;

    public SysUser() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "SysUser{" + username + ", role=" + role + "}";
    }
}
