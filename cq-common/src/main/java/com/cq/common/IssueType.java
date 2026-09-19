package com.cq.common;

/**
 * 问题类型（四大分析维度）
 * <p>
 * 取值与 {@link Issue#getType()} 的字符串一一对应，也对应 rule_config/issue 表的 type 列。
 */
public enum IssueType {

    /** Bug：空指针、并发问题、资源泄漏等 */
    BUG,

    /** 安全漏洞：注入、硬编码密钥、弱加密等 */
    SECURITY,

    /** 性能问题：循环内查库、冗余拷贝等 */
    PERFORMANCE,

    /** 编码规范：命名、长度、复杂度等 */
    STYLE;

    /**
     * 安全解析，无法识别时返回 null 而非抛异常
     * @param value 字符串值
     * @return 对应枚举，无法识别时为 null
     */
    public static IssueType parse(String value) {
        if (value == null) {
            return null;
        }
        for (IssueType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
