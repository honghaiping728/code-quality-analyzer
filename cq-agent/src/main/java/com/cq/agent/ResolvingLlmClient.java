package com.cq.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 运行时可切换的大模型客户端
 * <p>
 * 每次调用重新解析「当前生效的配置」，优先级为：**数据库启用项 > application.yml > 离线启发式**。
 * 委托对象在配置未变化时被复用（含其中的 {@code HttpClient}），仅在配置真正变化时重建一次，
 * 因此控制台里的保存 / 启用可以立即生效而无需重启，也不会带来每请求重建的开销。
 * <p>
 * 调用失败不做静默切换：真实客户端把失败如实返回为 {@code LlmResponse.success=false}，
 * 由 {@link CodeAgent} 沿用规则引擎判定，错误信息进 llm_call_log，保证「用的是哪个模型、
 * 为什么没成功」在界面上可查。
 */
public class ResolvingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ResolvingLlmClient.class);

    /** 没有任何可用配置时的兜底实现，无状态，可全局共用 */
    private static final LlmClient OFFLINE = new OfflineHeuristicLlmClient();

    /** 生效配置与其对应客户端：作为一个不可变整体发布，避免读到「新配置 + 旧客户端」的中间态 */
    private record Resolved(LlmSettings settings, LlmClient client) {}

    private final LlmConfigProvider configProvider;
    private final LlmSettings fallback;

    private volatile Resolved resolved;

    /**
     * @param configProvider 数据库配置来源
     * @param fallback application.yml 中的兜底配置（密钥可为空）
     */
    public ResolvingLlmClient(LlmConfigProvider configProvider, LlmSettings fallback) {
        this.configProvider = configProvider;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return current().name();
    }

    @Override
    public boolean available() {
        return current().available();
    }

    @Override
    public boolean offline() {
        return current().offline();
    }

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        return current().complete(systemPrompt, userPrompt);
    }

    /** 当前生效的客户端；生效配置变化时重建并缓存 */
    private LlmClient current() {
        LlmSettings effective = effectiveSettings();
        Resolved local = resolved;
        if (local != null && Objects.equals(local.settings(), effective)) {
            return local.client();
        }
        synchronized (this) {
            effective = effectiveSettings();
            if (resolved == null || !Objects.equals(resolved.settings(), effective)) {
                LlmClient client = effective == null ? OFFLINE : new OpenAiCompatibleLlmClient(
                        effective.baseUrl(), effective.apiKey(), effective.model(), effective.timeoutSeconds());
                resolved = new Resolved(effective, client);
                log.info("大模型客户端切换为：{}{}", client.name(),
                        effective == null ? "" : "（配置来源：" + effective.name() + "）");
            }
            return resolved.client();
        }
    }

    /** 生效配置：数据库启用项优先，其次 application.yml，都没有则 null（走离线） */
    private LlmSettings effectiveSettings() {
        LlmSettings fromDatabase = configProvider.current();
        if (fromDatabase != null && fromDatabase.usable()) {
            return fromDatabase;
        }
        return fallback != null && fallback.usable() ? fallback : null;
    }
}
