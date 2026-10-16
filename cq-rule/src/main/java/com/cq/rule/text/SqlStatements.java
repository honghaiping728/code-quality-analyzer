package com.cq.rule.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 语句切分
 * <p>
 * {@code UPDATE t SET a = 1 WHERE id = ?} 这类语句经常跨行书写，逐行匹配根本判断不出
 * 「这条语句有没有 WHERE」—— 只看单行的话，{@code UPDATE …} 与 {@code WHERE …} 分属两行，
 * 前者会被判成「没有 WHERE」而误报。
 * <p>
 * 这里按分号切分并跟踪每个语句的起始行号，同时跳过三种会让切分出错的内容：
 * 字符串字面量里的分号、{@code --} 与 {@code #} 行注释、{@code /* … *&#47;} 块注释。
 * 不追求完整的 SQL 词法实现，够用即可 —— 判错的代价是一次漏报或误报，而不是崩溃。
 */
final class SqlStatements {

    private static final Pattern WHERE = Pattern.compile("(?i)\\bwhere\\b");

    private SqlStatements() {}

    /**
     * 一条 SQL 语句
     * @param text 语句原文（已去掉注释）
     * @param startLine 语句中第一个非空白字符所在行（1 起），无法定位时为 -1
     */
    record Statement(String text, int startLine) {

        /** 语句的首个关键字，大写；空语句返回空串 */
        String verb() {
            String trimmed = text.stripLeading();
            int end = 0;
            while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
                end++;
            }
            return trimmed.substring(0, end).toUpperCase(Locale.ROOT);
        }

        boolean hasWhere() {
            return WHERE.matcher(text).find();
        }
    }

    /**
     * 切分 SQL 源码
     * @param source 源码原文
     * @return 非空语句列表，保持原文顺序
     */
    static List<Statement> split(String source) {
        List<Statement> statements = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        int line = 1;
        int startLine = -1;
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                lineComment = false;
            }
            if (lineComment) {
                continue;
            }
            if (blockComment) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;   // 不识别 '' 转义，最坏情况是把一条语句多切一段，不影响 WHERE 判定
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                if (startLine < 0) {
                    startLine = line;
                }
                current.append(c);
                continue;
            }
            if (c == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '#') {
                lineComment = true;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == ';') {
                add(statements, current, startLine);
                current.setLength(0);
                startLine = -1;
                continue;
            }
            if (startLine < 0 && !Character.isWhitespace(c)) {
                startLine = line;
            }
            current.append(c);
        }
        add(statements, current, startLine);
        return statements;
    }

    private static void add(List<Statement> target, StringBuilder buffer, int startLine) {
        String text = buffer.toString().strip();
        if (!text.isEmpty()) {
            target.add(new Statement(text, startLine));
        }
    }
}
