package com.cq.parser;

import com.cq.common.ast.AstSummary;
import com.github.javaparser.ast.CompilationUnit;

/**
 * 单次解析的完整结果
 * <p>
 * 同时携带三种形态，供规则按需取用，避免把大量细粒度事实塞进 {@link AstSummary}：
 * <ul>
 *     <li>{@link #getSummary()}：结构化模型，跨方法/跨类型的规则用（复杂度、类长度、命名）</li>
 *     <li>{@link #getUnit()}：原始 JavaParser AST，需要实参值、修饰符、字符串字面量等
 *         细粒度事实的规则用。{@code AstSummary} 的 {@code MethodCall} 只记录实参**个数**
 *         不记录实参**值**，因此「弱哈希」「硬编码密钥」这类规则无法只靠摘要实现</li>
 *     <li>{@link #getSource()}：源码原文，用于产出代码片段与计算行内容哈希（不引入
 *         JavaParser 到 cq-common）</li>
 * </ul>
 * <p>
 * 解析失败时 {@link #getUnit()} 为 null，但 {@link #getSummary()} 仍会带上
 * {@link AstSummary#getParseErrors()} 中的错误信息。
 */
public class ParsedFile {

    private AstSummary summary;
    private CompilationUnit unit;
    private String source;
    private String filePath;

    public ParsedFile() {}

    /** 是否成功拿到 AST（语法完全错误时为 false） */
    public boolean hasUnit() {
        return unit != null;
    }

    /**
     * 取指定行（1 起）的源码原文，行号越界返回空串
     * @param line 行号
     * @return 该行文本，已去除行尾换行
     */
    public String lineAt(int line) {
        if (source == null || line < 1) {
            return "";
        }
        int current = 1;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                if (current == line) {
                    return trimEol(source.substring(start, i));
                }
                current++;
                start = i + 1;
            }
        }
        return current == line ? trimEol(source.substring(start)) : "";
    }

    private static String trimEol(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return text.substring(0, end);
    }

    public AstSummary getSummary() { return summary; }
    public void setSummary(AstSummary summary) { this.summary = summary; }

    public CompilationUnit getUnit() { return unit; }
    public void setUnit(CompilationUnit unit) { this.unit = unit; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    @Override
    public String toString() {
        return "ParsedFile{path='" + filePath + "', unit=" + (unit != null)
                + ", sourceLines=" + (source == null ? 0 : source.lines().count()) + "}";
    }
}
