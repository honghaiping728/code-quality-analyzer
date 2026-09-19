package com.cq.common;

/**
 * 问题处理状态
 * <p>
 * 注意：标记为 {@link #FALSE_POSITIVE} 的问题**保留记录**而非删除，
 * 「误报反馈自学习」特性依赖这批数据做阈值调优。
 */
public enum IssueStatus {

    /** 待处理 */
    OPEN,

    /** 已确认为真实问题 */
    CONFIRMED,

    /** 误报：保留记录，用于反馈调优 */
    FALSE_POSITIVE,

    /** 已修复 */
    FIXED
}
