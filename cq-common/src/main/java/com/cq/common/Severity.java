package com.cq.common;

/**
 * 问题严重等级
 * <p>
 * 按 {@link #weight()} 从高到低：BLOCKER &gt; CRITICAL &gt; MAJOR &gt; MINOR。
 * 前端按此顺序加权展示，规则配置也允许按等级设阈值门槛。
 */
public enum Severity {

    /** 阻断级：必须立即修复，如信任所有证书、硬编码生产密钥 */
    BLOCKER(4),

    /** 严重：如 SQL 注入、资源泄漏 */
    CRITICAL(3),

    /** 主要：如空 catch、圈复杂度过高 */
    MAJOR(2),

    /** 次要：如命名不规范、缺 Javadoc */
    MINOR(1);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    /** 权重，用于排序与阈值比较 */
    public int weight() {
        return weight;
    }

    /**
     * 安全解析，无法识别时返回 null 而非抛异常
     * @param value 字符串值
     * @return 对应枚举，无法识别时为 null
     */
    public static Severity parse(String value) {
        if (value == null) {
            return null;
        }
        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(value.trim())) {
                return severity;
            }
        }
        return null;
    }
}
