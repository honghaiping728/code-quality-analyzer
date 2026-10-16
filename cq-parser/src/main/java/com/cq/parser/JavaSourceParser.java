package com.cq.parser;

import com.cq.common.lang.Language;

import java.util.Set;

/**
 * Java 源码解析器
 * <p>
 * 唯一的「真 AST」分支：委托给 {@link AstParserService}，产出 {@code CompilationUnit}
 * 与结构化摘要，供 42 条基于 JavaParser 节点的规则使用。
 * <p>
 * 本类无可变状态，可安全并发调用。
 */
public class JavaSourceParser implements SourceParser {

    private final AstParserService delegate;

    public JavaSourceParser() {
        this(new AstParserService());
    }

    public JavaSourceParser(AstParserService delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<Language> languages() {
        return Set.of(Language.JAVA);
    }

    @Override
    public ParsedFile parse(Language language, String source, String displayPath) {
        // language 恒为 JAVA：本解析器只认领 Java，注册表不会把别的语言派发过来。
        // 这里不校验也不改写，语言由注册表统一写回
        return delegate.parseDetailed(source == null ? "" : source, displayPath);
    }
}
