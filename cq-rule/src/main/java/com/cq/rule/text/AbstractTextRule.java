package com.cq.rule.text;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.lang.Language;
import com.cq.rule.AbstractRule;
import com.cq.rule.RuleContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于行扫描的规则基类
 * <p>
 * 非 Java 文件没有语法树，规则的判据退化为「某一行长什么样」。这类规则不共享
 * 任何算法，只共享三件容易写错的小事：
 * <ul>
 *     <li><b>逐行遍历</b>：必须走 {@link RuleContext#lines()} 的缓存，
 *         逐行调用 {@code lineAt(i)} 会把单文件复杂度从 O(行数) 拉成 O(字符数 × 行数)</li>
 *     <li><b>注释判断</b>：被注释掉的未完成标记、被注释掉的调试输出都不是问题，
 *         而各语言的注释符不同，集中在一处维护</li>
 *     <li><b>命中数封顶</b>：引擎的封顶是按「规则 × 文件」100 条，对文本规则太宽松，
 *         一个生成的前端 bundle 能让某条规则刷满整页</li>
 * </ul>
 */
public abstract class AbstractTextRule extends AbstractRule {

    /** 单文件单规则的命中数上限 */
    protected static final int MAX_FINDINGS_PER_FILE = 10;

    /**
     * 各语言的**整行**注释前缀
     * <p>
     * 取「行首第一个非空白字符起」是否以此开头来判断。这里可以包含 {@code *}
     * 这类只在续行出现的前缀（Javadoc 的 {@code  * 说明}、块注释内部行）。
     * JSON 没有注释语法，因此不在表中；Markdown 只认 {@code <!--}。
     */
    private static final Map<Language, String[]> COMMENT_PREFIXES = Map.ofEntries(
            Map.entry(Language.JAVA, new String[]{"//", "/*", "*/", "*"}),
            Map.entry(Language.JAVASCRIPT, new String[]{"//", "/*", "*/", "*"}),
            Map.entry(Language.TYPESCRIPT, new String[]{"//", "/*", "*/", "*"}),
            Map.entry(Language.VUE, new String[]{"//", "/*", "*/", "*", "<!--"}),
            Map.entry(Language.GO, new String[]{"//", "/*", "*/", "*"}),
            Map.entry(Language.CSS, new String[]{"/*", "*/", "*"}),
            Map.entry(Language.YAML, new String[]{"#"}),
            Map.entry(Language.PROPERTIES, new String[]{"#", ";", "!"}),
            Map.entry(Language.TOML, new String[]{"#"}),
            Map.entry(Language.SHELL, new String[]{"#"}),
            Map.entry(Language.PYTHON, new String[]{"#"}),
            Map.entry(Language.MAKEFILE, new String[]{"#"}),
            Map.entry(Language.DOCKERFILE, new String[]{"#"}),
            Map.entry(Language.SQL, new String[]{"--", "/*", "*/", "*"}),
            Map.entry(Language.XML, new String[]{"<!--"}),
            Map.entry(Language.MARKDOWN, new String[]{"<!--"}));

    /**
     * 各语言可用于**行内**的注释起始符
     * <p>
     * 比整行注释少掉 {@code *} 与块注释的结束符 —— 它们只在整行场景下才是注释。
     * 把 {@code *} 当作行内注释起点会把 SQL 的 {@code SELECT * FROM t} 从星号处截断，
     * 让「SELECT 后跟星号」这类判据永远匹配不到。
     */
    private static final Map<Language, String[]> INLINE_COMMENT_PREFIXES = Map.ofEntries(
            Map.entry(Language.JAVA, new String[]{"//", "/*"}),
            Map.entry(Language.JAVASCRIPT, new String[]{"//", "/*"}),
            Map.entry(Language.TYPESCRIPT, new String[]{"//", "/*"}),
            Map.entry(Language.VUE, new String[]{"//", "/*", "<!--"}),
            Map.entry(Language.GO, new String[]{"//", "/*"}),
            Map.entry(Language.CSS, new String[]{"/*"}),
            Map.entry(Language.YAML, new String[]{"#"}),
            Map.entry(Language.PROPERTIES, new String[]{"#", ";", "!"}),
            Map.entry(Language.TOML, new String[]{"#"}),
            Map.entry(Language.SHELL, new String[]{"#"}),
            Map.entry(Language.PYTHON, new String[]{"#"}),
            Map.entry(Language.MAKEFILE, new String[]{"#"}),
            Map.entry(Language.DOCKERFILE, new String[]{"#"}),
            Map.entry(Language.SQL, new String[]{"--", "/*"}),
            Map.entry(Language.XML, new String[]{"<!--"}),
            Map.entry(Language.MARKDOWN, new String[]{"<!--"}));

    private final Set<Language> languages;

    /**
     * @param languages 本规则适用的语言；与语言无关的规则传 {@link Language#scannable()}
     */
    protected AbstractTextRule(String id, String name, IssueType type, Severity severity,
                               double confidence, String description, Set<Language> languages) {
        super(id, name, type, severity, confidence, description);
        this.languages = Set.copyOf(languages);
    }

    @Override
    public Set<Language> languages() {
        return languages;
    }

    // ==================== 逐行扫描 ====================

    /** 源码行，第 n 行对应 {@code get(n - 1)} */
    protected static List<String> lines(RuleContext ctx) {
        return ctx.lines();
    }

    /** 该行从第一个非空白字符起是否以注释符开头 */
    protected static boolean isCommentLine(RuleContext ctx, String line) {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (String prefix : COMMENT_PREFIXES.getOrDefault(ctx.language(), new String[0])) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 空行或整行注释 */
    protected static boolean isBlankOrComment(RuleContext ctx, String line) {
        return line.isBlank() || isCommentLine(ctx, line);
    }

    /**
     * 去掉行内的注释尾巴
     * <p>
     * 只处理「行首尚未出现引号时就遇到注释符」的情况：{@code url: "a#b"} 里的 {@code #}
     * 属于值的一部分，不能被当成注释起点。
     * <p>
     * SQL 的 {@code --} 与 Shell 的 {@code #} 之外的记号（如 {@code //}）也可能出现在
     * URL 里，因此仅对「本语言定义的注释符」生效，且要求注释符前面有空白或是行首。
     * @param line 源码行
     * @return 去掉注释后的文本
     */
    protected static String stripInlineComment(RuleContext ctx, String line) {
        String[] prefixes = INLINE_COMMENT_PREFIXES.getOrDefault(ctx.language(), new String[0]);
        int cut = line.length();
        for (String prefix : prefixes) {
            int index = indexOfComment(line, prefix);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        return cut == line.length() ? line : line.substring(0, cut);
    }

    /**
     * 找注释符位置：要求它前面是行首或空白，且此前没有未闭合的引号
     * @return 位置，未找到返回 -1
     */
    private static int indexOfComment(String line, String prefix) {
        int from = 0;
        while (true) {
            int index = line.indexOf(prefix, from);
            if (index < 0) {
                return -1;
            }
            boolean atBoundary = index == 0 || Character.isWhitespace(line.charAt(index - 1));
            if (atBoundary && !insideQuotes(line, index)) {
                return index;
            }
            from = index + prefix.length();
        }
    }

    /** 位置 index 之前是否已有未闭合的单引号或双引号 */
    private static boolean insideQuotes(String line, int index) {
        boolean single = false;
        boolean doubled = false;
        for (int i = 0; i < index; i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;   // 转义字符，跳过下一个字符
            } else if (c == '\'') {
                single = !single;
            } else if (c == '"') {
                doubled = !doubled;
            }
        }
        return single || doubled;
    }

    // ==================== 命中构造 ====================

    /** 是否已达单文件命中上限 */
    protected static boolean capped(List<Issue> issues) {
        return issues.size() >= MAX_FINDINGS_PER_FILE;
    }
}
