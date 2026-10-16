package com.cq.parser;

import com.cq.common.lang.Language;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 解析器注册表
 * <p>
 * 扫描链路唯一的解析入口：按文件路径判定语言，派发给认领该语言的
 * {@link SourceParser}；没有任何解析器认领的语言一律落到兜底的
 * {@link PlainTextSourceParser}。新增语言时只需在构造时多传一个解析器，
 * 本类与调用方都不必改动。
 * <p>
 * 本类无可变状态（构造完成后内部映射只读），可安全并发调用。
 */
public class SourceParserRegistry {

    /** 语言 -> 认领它的解析器 */
    private final Map<Language, SourceParser> byLanguage = new EnumMap<>(Language.class);

    /** 未被任何解析器认领时的兜底解析器 */
    private final SourceParser fallback;

    /** 使用内置解析器集合：Java 走 AST，JSON/YAML/XML 做良构校验，其余按纯文本处理 */
    public SourceParserRegistry() {
        this(List.of(new JavaSourceParser(), new StructuredSourceParser()), new PlainTextSourceParser());
    }

    /**
     * 内置的解析器装配
     * <p>
     * 兜底解析器在此**显式**传入，而不是靠「{@code languages()} 为空即兜底」这类
     * 需要看实现才能推断的约定。
     * @return 装配好的注册表
     */
    public static SourceParserRegistry defaults() {
        return new SourceParserRegistry(
                List.of(new JavaSourceParser(), new StructuredSourceParser()),
                new PlainTextSourceParser());
    }

    /**
     * @param parsers 认领语言的解析器，靠前者优先（同一语言被多个解析器认领时取首个）
     * @param fallback 兜底解析器，用于未被认领的语言
     */
    public SourceParserRegistry(List<SourceParser> parsers, SourceParser fallback) {
        this.fallback = fallback;
        for (SourceParser parser : parsers) {
            for (Language language : parser.languages()) {
                byLanguage.putIfAbsent(language, parser);
            }
        }
    }

    /**
     * 解析一份源码
     * <p>
     * 语言由 {@code displayPath} 推导并写回结果 —— 规则引擎正是按它来决定某条规则
     * 是否适用，因此即便兜底解析器不关心语言，这里也必须统一赋值。
     * @param source 源码原文
     * @param displayPath 展示用路径，可为 null（此时语言为 {@link Language#UNKNOWN}）
     * @return 解析结果，永不返回 null
     */
    public ParsedFile parse(String source, String displayPath) {
        Language language = Language.of(displayPath);
        ParsedFile parsed = byLanguage.getOrDefault(language, fallback).parse(language, source, displayPath);
        parsed.setLanguage(language);   // 兜底解析器也会回写，这里再确认一次，语言永不为 null
        return parsed;
    }

    /**
     * 全部受支持的语言
     * <p>
     * 供上传接口回显「支持哪些类型」这类提示使用。
     * @return 受支持语言，不含 {@link Language#UNKNOWN}
     */
    public List<Language> supportedLanguages() {
        List<Language> languages = new ArrayList<>(byLanguage.keySet());
        languages.sort(java.util.Comparator.comparing(Enum::name));
        return languages;
    }
}
