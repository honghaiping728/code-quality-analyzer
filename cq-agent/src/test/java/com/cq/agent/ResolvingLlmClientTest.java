package com.cq.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时切换的大模型客户端测试
 * <p>
 * 这里的语义是「配置发生变化后，下一次调用就该用新配置」——它支撑控制台上
 * 保存 / 启用 API Key 立即生效，因此逐条钉住优先级与回退行为。
 */
class ResolvingLlmClientTest {

    private static final LlmSettings YML_WITH_KEY = new LlmSettings(
            "application.yml", "https://api.deepseek.com/v1", "sk-yml-key", "deepseek-chat", 60);

    private static final LlmSettings YML_WITHOUT_KEY = new LlmSettings(
            "application.yml", "https://api.deepseek.com/v1", "", "deepseek-chat", 60);

    private static final LlmSettings DB_SETTINGS = new LlmSettings(
            "DeepSeek 生产", "https://api.deepseek.com/v1", "sk-db-key", "qwen-plus", 60);

    /** 可切换的配置来源桩 */
    private static final class StubProvider implements LlmConfigProvider {

        private LlmSettings settings;

        StubProvider(LlmSettings settings) {
            this.settings = settings;
        }

        void switchTo(LlmSettings settings) {
            this.settings = settings;
        }

        @Override
        public LlmSettings current() {
            return settings;
        }
    }

    @Test
    @DisplayName("数据库与 yml 都没有密钥时走离线启发式")
    void fallsBackToOfflineWhenNothingConfigured() {
        ResolvingLlmClient client = new ResolvingLlmClient(new StubProvider(null), YML_WITHOUT_KEY);

        assertTrue(client.offline());
        assertTrue(client.name().contains("离线启发式"), client.name());
    }

    @Test
    @DisplayName("仅 yml 配置密钥时使用真实客户端")
    void usesFallbackSettingsFromYml() {
        ResolvingLlmClient client = new ResolvingLlmClient(new StubProvider(null), YML_WITH_KEY);

        assertFalse(client.offline());
        assertTrue(client.name().contains("deepseek-chat"), client.name());
    }

    @Test
    @DisplayName("数据库启用项优先于 yml")
    void databaseSettingsWinOverYml() {
        ResolvingLlmClient client = new ResolvingLlmClient(new StubProvider(DB_SETTINGS), YML_WITH_KEY);

        assertFalse(client.offline());
        assertTrue(client.name().contains("qwen-plus"), client.name());
    }

    @Test
    @DisplayName("启用项字段不完整时回退 yml，而不是静默降级为离线")
    void fallsBackToYmlWhenDatabaseRowIsIncomplete() {
        LlmSettings incomplete = new LlmSettings("残配置", "https://api.deepseek.com/v1", "", "qwen-plus", 60);
        ResolvingLlmClient client = new ResolvingLlmClient(new StubProvider(incomplete), YML_WITH_KEY);

        assertFalse(client.offline());
        assertTrue(client.name().contains("deepseek-chat"), client.name());
    }

    @Test
    @DisplayName("配置切换后立即生效：无需重启，下一次调用用新配置")
    void picksUpConfigurationChangeImmediately() {
        StubProvider provider = new StubProvider(null);
        ResolvingLlmClient client = new ResolvingLlmClient(provider, YML_WITHOUT_KEY);
        assertTrue(client.offline());

        provider.switchTo(DB_SETTINGS);
        assertFalse(client.offline());
        assertTrue(client.name().contains("qwen-plus"), client.name());

        provider.switchTo(null);
        assertTrue(client.offline());
    }
}
