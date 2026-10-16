package com.cq.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则配置的对外视图
 * <p>
 * 比 {@link RuleConfig} 多一个「适用语言」字段，供规则配置页展示。
 * <p>
 * <b>为什么不直接往 {@link RuleConfig} 加字段</b>：那个类同时是 MyBatis-Plus 的实体，
 * 本项目的模型全部按「字段名 = 列名」的约定映射，且没有一个类用过 {@code @TableField}。
 * 一旦给它加一个数据库里没有的字段，MyBatis-Plus 会把该字段拼进 {@code SELECT} 列表，
 * 查询直接报「Unknown column」。更麻烦的是失败方式：{@code RuleConfigService} 对查询异常
 * 有兜底（数据库不可用时回退到默认值），因此抛出的异常会被静默吞掉，
 * 表现成「数据库里配的规则启停全部失效」而不是报错，极难排查。
 * <p>
 * 独立出一个子类后，实体保持与表结构一一对应，多出来的字段只走 JSON 序列化。
 */
public class RuleConfigView extends RuleConfig {

    /** 适用语言，如 {@code [JAVA]} 或 {@code [YAML, PROPERTIES, TOML]} */
    private List<String> languages = new ArrayList<>();

    public RuleConfigView() {}

    public RuleConfigView(String ruleId, String ruleName, String category) {
        super(ruleId, ruleName, category);
    }

    public List<String> getLanguages() { return languages; }

    public void setLanguages(List<String> languages) {
        this.languages = languages == null ? new ArrayList<>() : languages;
    }
}
