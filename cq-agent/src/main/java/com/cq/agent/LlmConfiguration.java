package com.cq.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 大模型客户端装配
 * <p>
 * 按是否配置了 API Key 决定使用真实客户端还是离线兜底客户端。这个选择放在配置层
 * 而非散落在调用处，保证上层代码对二者无感知。
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    /**
     * @param baseUrl OpenAI 兼容接口地址，如 https://api.deepseek.com/v1
     * @param apiKey 密钥；留空则启用离线兜底
     * @param model 模型名
     * @param timeoutSeconds 超时
     * @return 大模型客户端
     */
    @Bean
    public LlmClient llmClient(
            @Value("${cq.llm.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${cq.llm.api-key:}") String apiKey,
            @Value("${cq.llm.model:deepseek-chat}") String model,
            @Value("${cq.llm.timeout-seconds:60}") int timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 cq.llm.api-key，大模型能力将退化为离线启发式实现");
            return new OfflineHeuristicLlmClient();
        }
        log.info("已启用大模型客户端：{} @ {}", model, baseUrl);
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey, model, timeoutSeconds);
    }
}
