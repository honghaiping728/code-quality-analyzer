package com.cq.agent;

import com.cq.common.model.LlmApiKey;

import java.util.Date;

/**
 * API Key 配置的对外视图
 * <p>
 * 刻意与实体分开：密钥字段根本不存在于此类型中，接口响应不存在「忘记掩码」的路径。
 */
public class LlmApiKeyView {

    private Long id;
    private String name;
    private String baseUrl;
    private String model;
    private String keyPreview;      // 掩码后的密钥，如 sk-…cdef
    private boolean active;
    private Date createTime;
    private Date updateTime;

    public static LlmApiKeyView from(LlmApiKey row) {
        LlmApiKeyView view = new LlmApiKeyView();
        view.id = row.getId();
        view.name = row.getName();
        view.baseUrl = row.getBaseUrl();
        view.model = row.getModel();
        view.keyPreview = mask(row.getApiKey());
        view.active = row.isActive();
        view.createTime = row.getCreateTime();
        view.updateTime = row.getUpdateTime();
        return view;
    }

    /** 掩码：保留前 3 后 4，过短则整体隐去 */
    static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "…" + apiKey.substring(apiKey.length() - 4);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getKeyPreview() { return keyPreview; }
    public void setKeyPreview(String keyPreview) { this.keyPreview = keyPreview; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
