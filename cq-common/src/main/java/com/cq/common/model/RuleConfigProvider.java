package com.cq.common.model;

/**
 * 规则配置来源
 * <p>
 * 刻意定义在 cq-common 而非某个具体模块：扫描模块需要「当前的规则配置」，
 * 而配置的存储与维护属于管理模块，用接口隔开可避免 cq-scan 反向依赖 cq-admin。
 */
public interface RuleConfigProvider {

    /**
     * 取当前生效的规则配置
     * @return 规则配置，不得返回 null
     */
    RuleConfigSet current();
}
