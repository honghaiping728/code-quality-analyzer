package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 阈值配置行
 * <p>
 * 以单行 key-value 存储规则阈值，避免每加一个可调参数就要改表结构。
 * 启动时由 cq-admin 读入并装配成 {@link RuleThresholds}。
 */
public class RuleThreshold implements Serializable {

    private Long id;
    private String configKey;
    private String configValue;
    private String valueType = "INT";   // INT | STRING | BOOLEAN | LIST
    private String description;
    private Date createTime;
    private Date updateTime;

    public RuleThreshold() {}

    public RuleThreshold(String configKey, String configValue, String valueType, String description) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.valueType = valueType;
        this.description = description;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }

    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "RuleThreshold{" + configKey + "=" + configValue + "}";
    }
}
