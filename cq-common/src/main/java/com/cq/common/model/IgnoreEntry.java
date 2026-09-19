package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 忽略项
 * <p>
 * 匹配时使用 {@code lineHash}（源码行内容的 SHA-256）而非裸行号：行号会随上方代码编辑
 * 整体位移，仅按行号匹配会导致忽略在第一次提交后就失效或误忽略。
 * {@link #lineHash} 为 null 时表示忽略整个文件（配合规则 ID 则为忽略该文件中的该规则）。
 */
public class IgnoreEntry implements Serializable {

    private Long id;
    private String ruleId;          // null 表示忽略该路径下所有规则
    private String filePattern;     // 路径 glob，如 **/test/**
    private String lineHash;        // null 表示整个文件
    private String reason;
    private Date expireTime;        // null 表示永久
    private boolean enabled = true;
    private Date createTime;

    public IgnoreEntry() {}

    public IgnoreEntry(String ruleId, String filePattern, String lineHash, String reason) {
        this.ruleId = ruleId;
        this.filePattern = filePattern;
        this.lineHash = lineHash;
        this.reason = reason;
    }

    /** 是否已过期 */
    public boolean isExpired() {
        return expireTime != null && expireTime.before(new Date());
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getFilePattern() { return filePattern; }
    public void setFilePattern(String filePattern) { this.filePattern = filePattern; }

    public String getLineHash() { return lineHash; }
    public void setLineHash(String lineHash) { this.lineHash = lineHash; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Date getExpireTime() { return expireTime; }
    public void setExpireTime(Date expireTime) { this.expireTime = expireTime; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "IgnoreEntry{rule=" + ruleId + ", path=" + filePattern + ", lineHash=" + lineHash + "}";
    }
}
