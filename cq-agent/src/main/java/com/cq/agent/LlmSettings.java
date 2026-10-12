package com.cq.agent;

/**
 * 一套大模型接入参数
 * <p>
 * 数据库中的启用配置与 application.yml 兜底都装配成它，比较「生效配置是否变化」时
 * 直接依赖 record 的 equals —— 重复保存同样的值不会触发客户端重建。
 *
 * @param name 配置名称（来自 application.yml 的兜底为 "application.yml"），用于日志与展示
 * @param baseUrl OpenAI 兼容接口地址，如 https://api.deepseek.com/v1
 * @param apiKey 密钥
 * @param model 模型名
 * @param timeoutSeconds 连接超时秒数
 */
public record LlmSettings(String name, String baseUrl, String apiKey, String model, int timeoutSeconds) {

    /** 是否足以发起真实调用 */
    public boolean usable() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }
}
