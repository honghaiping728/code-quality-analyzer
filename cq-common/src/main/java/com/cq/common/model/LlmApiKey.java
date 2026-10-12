package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 大模型 API Key 配置
 * <p>
 * 一条记录即一套「OpenAI 兼容服务」的接入参数（地址 / 密钥 / 模型）。允许多条并存，
 * 但同一时刻至多一条 {@link #active} 为 true：运行时优先级为
 * 「启用中的数据库配置 > application.yml > 离线启发式」。
 * <p>
 * 密钥明文存本地库（与 {@code repo.credential} 的现状一致），但对外的接口只返回掩码，
 * 因此 {@link #toString()} 也不输出密钥原文。
 */
public class LlmApiKey implements Serializable {

    private Long id;
    private String name;        // 配置名称，全表唯一
    private String baseUrl;     // 接口地址，如 https://api.deepseek.com/v1
    private String apiKey;      // 密钥
    private String model;       // 模型名，如 deepseek-chat
    private boolean active;     // 是否当前启用
    private Date createTime;
    private Date updateTime;

    public LlmApiKey() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "LlmApiKey{name=" + name + ", model=" + model + ", baseUrl=" + baseUrl
                + ", active=" + active + ", apiKey=***}";
    }
}
