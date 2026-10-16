package com.cq.parser;

import com.cq.common.lang.Language;

import java.util.Set;

/**
 * 纯文本兜底解析器
 * <p>
 * 不做任何语法分析，只把源码原样装进 {@link ParsedFile} —— 基于行扫描的规则
 * （行尾空白、遗留的合并冲突标记、明文口令等）只需要源码原文与行号定位，
 * 本来就不需要语法树。
 * <p>
 * {@link #languages()} 返回空集：本类不认领任何语言，由
 * {@link SourceParserRegistry#defaults()} 显式指定为兜底解析器。兜底是注册表构造时
 * 传入的，而非由「空集」推断 —— 这样新增语言时不必回来改这里。
 */
public class PlainTextSourceParser implements SourceParser {

    @Override
    public Set<Language> languages() {
        return Set.of();
    }

    @Override
    public ParsedFile parse(Language language, String source, String displayPath) {
        ParsedFile parsed = new ParsedFile();
        parsed.setLanguage(language);
        parsed.setFilePath(displayPath);
        parsed.setSource(source == null ? "" : source);
        parsed.getSummary().setFilePath(displayPath);
        return parsed;
    }
}
