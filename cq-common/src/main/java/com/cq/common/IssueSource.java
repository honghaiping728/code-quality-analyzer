package com.cq.common;

/**
 * 问题来源（双引擎标记）
 */
public enum IssueSource {

    /** 规则引擎确定性检出 */
    RULE,

    /** LLM 语义分析检出 */
    LLM,

    /** 双引擎结果融合后确认 */
    FUSED
}
