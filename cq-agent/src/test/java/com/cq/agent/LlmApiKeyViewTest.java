package com.cq.agent;

import com.cq.common.model.LlmApiKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * API Key 掩码测试
 * <p>
 * 掩码是密钥不外泄的唯一屏障：既要在页面上可辨认（前后缀保留），
 * 又不能反推出原文，边界值用测试钉死。
 */
class LlmApiKeyViewTest {

    @Test
    @DisplayName("空值与空白返回空串")
    void masksBlank() {
        assertEquals("", LlmApiKeyView.mask(null));
        assertEquals("", LlmApiKeyView.mask("   "));
    }

    @Test
    @DisplayName("过短的密钥整体隐去")
    void masksShortKey() {
        assertEquals("****", LlmApiKeyView.mask("sk-abc"));
        assertEquals("****", LlmApiKeyView.mask("12345678"));
    }

    @Test
    @DisplayName("常规密钥保留前 3 后 4")
    void masksNormalKey() {
        assertEquals("sk-…cdef", LlmApiKeyView.mask("sk-abcdefcdef"));
        assertEquals("abc…fghi", LlmApiKeyView.mask("abcdefghi"));
    }

    @Test
    @DisplayName("掩码结果不包含密钥原文")
    void maskedResultDoesNotLeakSecret() {
        String secret = "sk-1234567890abcdef";
        String masked = LlmApiKeyView.mask(secret);

        assertFalse(masked.contains(secret));
        assertFalse(masked.contains("1234567890"));
    }

    @Test
    @DisplayName("视图不含密钥原文，只有掩码预览")
    void viewCarriesOnlyPreview() {
        LlmApiKey row = new LlmApiKey();
        row.setId(1L);
        row.setName("DeepSeek 生产");
        row.setBaseUrl("https://api.deepseek.com/v1");
        row.setModel("deepseek-chat");
        row.setApiKey("sk-1234567890abcdef");
        row.setActive(true);

        LlmApiKeyView view = LlmApiKeyView.from(row);

        assertEquals("sk-…cdef", view.getKeyPreview());
        assertEquals("DeepSeek 生产", view.getName());
        assertEquals("deepseek-chat", view.getModel());
        assertFalse(row.toString().contains("1234567890abcdef"));
    }
}
