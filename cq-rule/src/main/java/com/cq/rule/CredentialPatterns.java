package com.cq.rule;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 凭证识别的共用判据
 * <p>
 * 「这个值算不算凭证」有两套并行的规则要回答：Java 源码里的
 * {@code SEC.HARDCODED_CREDENTIAL}（看字符串字面量）与配置文件里的
 * {@code SEC.CONFIG_PLAINTEXT_SECRET}（看 {@code key: value} 的值）。两者若各写一套
 * 占位值名单，迟早会漂移成「同一个 {@code changeme} 在 Java 里放过、在 YAML 里报出」，
 * 因此判据收敛到这里。
 */
public final class CredentialPatterns {

    private CredentialPatterns() {}

    /**
     * 疑似凭证的名称模式：password / secret / token / apiKey / accessKey / privateKey …
     * <p>
     * 分隔符同时接受 {@code _}、{@code -} 与 {@code .}：配置文件的键名惯用点号
     * （{@code api.key}、{@code spring.datasource.password}），而 Java 标识符里
     * 不可能出现点号，多这一种字符对源码侧的行为没有影响。
     */
    public static final Pattern CREDENTIAL_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|api[._-]?key|access[._-]?key"
                    + "|credential|private[._-]?key).*");

    /** 明文凭证的占位值，不应报告 */
    public static final List<String> PLACEHOLDER_VALUES = List.of(
            "", "test", "changeme", "todo", "xxx", "your_password", "password",
            "example", "placeholder", "123456");

    /** 纯掩码值：{@code ******} 或 {@code xxxxxx} 形态，说明这里本来就只是示意 */
    private static final Pattern MASKED_VALUE = Pattern.compile("\\*{3,}|x{4,}", Pattern.CASE_INSENSITIVE);

    /**
     * 判断一个值是不是占位而非真实凭证
     * <p>
     * 三类都算占位：名单里的示例值、{@code ${ENV_VAR}} 这类引用、以及纯掩码。
     * 这是整组凭证规则降噪的主开关 —— 名单一旦放宽，规则立刻会被使用者关掉。
     * @param value 待判定的值，可为 null
     * @return 是否属于占位
     */
    public static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_VALUES.contains(normalized)) {
            return true;
        }
        // ${...} 形式的占位符，与 #{...} 形式的 MyBatis 参数
        if (normalized.startsWith("${") || normalized.startsWith("#{")) {
            return true;
        }
        return MASKED_VALUE.matcher(normalized).matches();
    }
}
