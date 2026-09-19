package com.cq.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容协议的大模型客户端
 * <p>
 * 用 JDK 内置的 {@link HttpClient} 实现，不引入任何第三方 HTTP 依赖。
 * OpenAI 的 {@code /chat/completions} 协议已成为事实标准，DeepSeek、通义千问、
 * Kimi、智谱等国内厂商均兼容，因此只需切换 {@code base-url} 与 {@code model}
 * 即可对接，无需为每家写一个客户端。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param baseUrl 接口基础地址，如 {@code https://api.deepseek.com/v1}
     * @param apiKey 密钥
     * @param model 模型名
     * @param timeoutSeconds 超时秒数
     */
    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .build();
    }

    @Override
    public String name() {
        return "OpenAI 兼容 (" + model + ")";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        long startedAt = System.currentTimeMillis();
        if (!available()) {
            return LlmResponse.failure("未配置 API Key 或 base-url", model, 0);
        }
        try {
            String body = buildRequestBody(systemPrompt, userPrompt);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startedAt;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("LLM 调用返回 {}: {}", response.statusCode(), abbreviate(response.body()));
                return LlmResponse.failure("HTTP " + response.statusCode(), model, duration);
            }
            return parseResponse(response.body(), duration);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmResponse.failure("调用被中断", model, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.toString());
            return LlmResponse.failure(e.getMessage(), model, System.currentTimeMillis() - startedAt);
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.2);   // 代码场景要稳定输出，不宜发散
        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        return objectMapper.writeValueAsString(root);
    }

    private LlmResponse parseResponse(String body, long duration) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return LlmResponse.failure("响应中没有 choices", model, duration);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            LlmResponse response = LlmResponse.of(content, model, duration);
            JsonNode usage = root.path("usage");
            response.setPromptTokens(usage.path("prompt_tokens").asInt(0));
            response.setCompletionTokens(usage.path("completion_tokens").asInt(0));
            response.setTotalTokens(usage.path("total_tokens").asInt(0));
            return response;
        } catch (Exception e) {
            return LlmResponse.failure("响应解析失败: " + e.getMessage(), model, duration);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
