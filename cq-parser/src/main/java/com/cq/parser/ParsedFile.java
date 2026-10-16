package com.cq.parser;

import com.cq.common.ast.AstSummary;
import com.cq.common.lang.Language;
import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.List;

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
 * <p>
 * {@link #getLanguage()} 只在 Java 上有真实 AST；其余语言为纯文本或仅做良构校验，
 * {@link #getUnit()} 恒为 null，{@link #getSource()} 与行号定位照常可用
 * —— 基于行扫描的规则正是靠这两者工作。
 */
public class ParsedFile {

    /**
     * 结构化摘要
     * <p>
     * 初值取空摘要而非 null：非 Java 文件不产出任何 AST 结构，但扫描链路仍要读
     * {@code getSummary().isParsed()} 与 {@code getParseErrors()} 来决定文件状态，
     * 留 null 会在「解析器只做了良构校验」这条路径上直接抛空指针。
     */
    private AstSummary summary = new AstSummary();
    private CompilationUnit unit;
    private String source;
    private String filePath;

    /**
     * 源码语言
     * <p>
     * 初值取 {@link Language#JAVA} 而非 null：本类历史上只由 Java 解析器构造，
     * 既有调用方（含大量单元测试）直接从 {@code AstParserService} 拿 {@code ParsedFile}
     * 而不经过解析器注册表。若此处留 null，规则引擎的按语言过滤会把 42 条 Java 规则
     * 全部跳过，整条既有测试链路会静默失效。
     */
    private Language language = Language.JAVA;

    /** 行缓存，懒加载；源码被替换时置空 */
    private volatile List<String> lineCache;

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
        List<String> all = lines();
        return line >= 1 && line <= all.size() ? all.get(line - 1) : "";
    }

    /**
     * 全部源码行，第 n 行对应 {@code get(n - 1)}
     * <p>
     * 带缓存。基于行扫描的规则会逐行遍历整个文件，若每行都重新扫描一遍源码字符串，
     * 单文件的复杂度会从 O(行数) 退化成 O(字符数 × 行数)。
     * @return 按行拆分的源码，无源码时为空列表
     */
    public List<String> lines() {
        List<String> cached = lineCache;
        if (cached == null) {
            cached = splitLines(source);
            lineCache = cached;
        }
        return cached;
    }

    /**
     * 行数
     * @return 源码行数，无源码时为 0
     */
    public int lineCount() {
        return lines().size();
    }

    /**
     * 按 {@code \n} 拆分并去掉行尾的 {@code \r}
     * <p>
     * 刻意不用 {@link String#lines()}：它会把末尾的空行丢掉，
     * 导致「文件末尾缺换行」这类规则看不到真实的行数。
     */
    private static List<String> splitLines(String source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String[] parts = source.split("\n", -1);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(trimEol(part));
        }
        return result;
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

    public void setSource(String source) {
        this.source = source;
        this.lineCache = null;   // 源码变了，行缓存必须失效
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    /** 源码语言，永不为 null（未显式设置时按 Java 处理） */
    public Language getLanguage() { return language; }

    /** 设置语言，传入 null 时回退为 {@link Language#JAVA} */
    public void setLanguage(Language language) {
        this.language = language == null ? Language.JAVA : language;
    }

    @Override
    public String toString() {
        return "ParsedFile{path='" + filePath + "', unit=" + (unit != null)
                + ", sourceLines=" + (source == null ? 0 : source.lines().count()) + "}";
    }
}
