package com.cq.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ParsedFile 测试
 * <p>
 * 重点验证 {@link ParsedFile#lineAt} 的边界行为 —— 规则引擎靠它把行号还原成
 * 代码片段，行尾与越界处理出错会让修复建议里的代码片段整体错位。
 * 其余用例锁定「摘要 + 原始 AST + 源码」三件套的装配约定。
 */
class ParsedFileTest {

    // ==================== lineAt ====================

    @Test
    @DisplayName("lineAt 按 1 起行号取行，越界返回空串")
    void lineAtReturnsRequestedLine() {
        ParsedFile file = new ParsedFile();
        file.setSource("one\ntwo\nthree");
        assertEquals("one", file.lineAt(1));
        assertEquals("two", file.lineAt(2));
        assertEquals("three", file.lineAt(3));
        assertEquals("", file.lineAt(0));
        assertEquals("", file.lineAt(-1));
        assertEquals("", file.lineAt(4));
    }

    @Test
    @DisplayName("CRLF 行尾的 \\r 会被去掉")
    void lineAtStripsCarriageReturn() {
        ParsedFile file = new ParsedFile();
        file.setSource("a\r\nb\r\n");
        assertEquals("a", file.lineAt(1));
        assertEquals("b", file.lineAt(2));
        assertEquals("", file.lineAt(3));
    }

    @Test
    @DisplayName("末行没有换行符时仍可取到")
    void lineAtHandlesMissingTrailingNewline() {
        ParsedFile file = new ParsedFile();
        file.setSource("a\nb");
        assertEquals("b", file.lineAt(2));
    }

    @Test
    @DisplayName("未设置源码时任何行号都返回空串")
    void lineAtWithoutSourceReturnsEmpty() {
        assertEquals("", new ParsedFile().lineAt(1));
    }

    // ==================== 与解析服务的衔接 ====================

    @Test
    @DisplayName("parseDetailed 保留源码原文与原始 AST")
    void parseDetailedKeepsSourceAndUnit() {
        String source = "package t;\nclass A {\n    void f() { }\n}\n";
        ParsedFile file = new AstParserService().parseDetailed(source);
        assertTrue(file.hasUnit());
        assertNotNull(file.getUnit());
        assertEquals(source, file.getSource());
        assertTrue(file.getSummary().isParsed());
        assertEquals("    void f() { }", file.lineAt(3));
    }

    @Test
    @DisplayName("parseDetailed 的展示路径同时落到 ParsedFile 与摘要")
    void parseDetailedUsesDisplayPath() {
        ParsedFile file = new AstParserService().parseDetailed("class A { }", "上传/示例.java");
        assertEquals("上传/示例.java", file.getFilePath());
        assertEquals("上传/示例.java", file.getSummary().getFilePath());
    }

    @Test
    @DisplayName("parseDetailed(File) 按 UTF-8 读取并保留路径")
    void parseDetailedReadsFileAsUtf8(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        String content = "package t;\nclass Sample {\n    String s = \"中文内容\";\n}\n";
        Files.writeString(file, content);
        ParsedFile parsed = new AstParserService().parseDetailed(file.toFile());
        assertEquals(content, parsed.getSource());
        assertEquals(file.toString(), parsed.getFilePath());
        assertTrue(parsed.lineAt(3).contains("中文内容"));
    }

    @Test
    @DisplayName("无法恢复的源码记录 parseErrors，摘要为空但流程不中断")
    void unparseableSourceIsRecordedNotThrown() {
        ParsedFile file = new AstParserService().parseDetailed("class A { void f( { } }");
        assertFalse(file.getSummary().isParsed());
        assertFalse(file.getSummary().getParseErrors().isEmpty(), "语法错误应记录在 parseErrors");
        // JavaParser 对无法恢复的源码给出空 CU（而不是 null），
        // 后续规则看到的是「无类型、无方法」的摘要
        assertTrue(file.getSummary().getClasses().isEmpty());
        assertTrue(file.getSummary().getMethods().isEmpty());
    }

    @Test
    @DisplayName("默认构造的 ParsedFile 没有 AST")
    void defaultParsedFileHasNoUnit() {
        assertFalse(new ParsedFile().hasUnit());
        assertNull(new ParsedFile().getUnit());
    }
}
