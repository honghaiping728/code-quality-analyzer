package com.cq.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 大模型客户端装配
 * <p>
 * 装配出的 {@link ResolvingLlmClient} 在每次调用时解析当前生效的配置，优先级为
 * 「数据库启用项（控制台「大模型配置」页） > application.yml > 离线启发式」。
 * 选择放在配置层而非散落在调用处，保证上层代码对具体实现无感知。
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    /**
     * @param configProvider 数据库中的启用配置
     * @param baseUrl OpenAI 兼容接口地址，如 https://api.deepseek.com/v1
     * @param apiKey 密钥；留空则回退离线兜底
     * @param model 模型名
     * @param timeoutSeconds 超时
     * @return 大模型客户端
     */
    @Bean
    public LlmClient llmClient(
            LlmConfigProvider configProvider,
            @Value("${cq.llm.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${cq.llm.api-key:}") String apiKey,
            @Value("${cq.llm.model:deepseek-chat}") String model,
            @Value("${cq.llm.timeout-seconds:60}") int timeoutSeconds) {
        ResolvingLlmClient client = new ResolvingLlmClient(configProvider,
                new LlmSettings("application.yml", baseUrl, apiKey, model, timeoutSeconds));
        if (client.offline()) {
            log.warn("未配置可用的大模型密钥（控制台配置与 cq.llm.api-key 均为空），"
                    + "大模型能力将退化为离线启发式实现");
        } else {
            log.info("大模型客户端已就绪：{}", client.name());
        }
        return client;
    }
}
