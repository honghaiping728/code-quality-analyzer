package com.cq.agent;

/**
 * 当前生效的大模型配置来源
 * <p>
 * 对应规则配置的 {@code RuleConfigProvider}：由数据库支撑的实现负责缓存与失效，
 * 消费方（{@link ResolvingLlmClient}）据此决定实际使用的客户端。
 * <p>
 * 实现约定：没有启用项、或数据库不可用时返回 {@code null}，**不得抛异常** ——
 * 上层会据此回退到 application.yml，再回退到离线启发式。
 */
public interface LlmConfigProvider {

    /**
     * @return 当前启用且字段完整的配置；没有可用配置时返回 null
     */
    LlmSettings current();
}
