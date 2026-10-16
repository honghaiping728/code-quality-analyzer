package com.cq.parser;

import com.cq.common.lang.Language;

import java.util.Set;

/**
 * 源码解析器（插件）
 * <p>
 * 把「某种语言的文件怎么变成 {@link ParsedFile}」收敛到一个接口后面。Java 用 JavaParser
 * 建真实 AST；配置文件只做良构校验；其余语言原样透传即可 —— 它们由基于行扫描的规则处理，
 * 不需要语法树。
 * <p>
 * 新增一门语言时只需实现本接口并注册进 {@link SourceParserRegistry}，
 * 扫描链路与规则引擎都不必改动。
 * <p>
 * 实现类必须**无状态且线程安全**：扫描按文件并发调用 {@link #parse}。
 */
public interface SourceParser {

    /**
     * 本解析器认领的语言
     * <p>
     * 返回空集表示不认领任何语言，此时该解析器只在注册表中当作**兜底**使用
     * （见 {@link PlainTextSourceParser}）。
     * @return 语言集合，不可为 null
     */
    Set<Language> languages();

    /**
     * 解析一份源码
     * <p>
     * 语言由注册表统一识别后传入并写回结果，不由各解析器自行推导 —— 「谁负责写
     * {@link ParsedFile#getLanguage()}」一旦含糊，规则引擎的按语言过滤就会静默失效。
     * @param language 已识别出的语言
     * @param source 源码原文
     * @param displayPath 展示用路径，用于规则中的路径匹配，可为 null
     * @return 解析结果，永不返回 null
     */
    ParsedFile parse(Language language, String source, String displayPath);
}
