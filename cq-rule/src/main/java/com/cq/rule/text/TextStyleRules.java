package com.cq.rule.text;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.lang.Language;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规范维度规则（文本扫描版，3 条）
 * <p>
 * 三条都作用于**全部语言**，包括 Java —— 行尾空白、文件末尾缺换行、超长行在
 * Java 文件里同样是问题，只是此前的 42 条 Java 规则都建立在 AST 之上、看不到这类
 * 纯文本层面的瑕疵。
 * <p>
 * 一致地排除 Markdown：表格与长链接天然无法按宽度折行，行尾两个空格更是它表示
 * 硬换行的语法，纳入检查只会制造必然被忽略的告警。
 */
public final class TextStyleRules {

    private TextStyleRules() {}

    /** 全部文本规范规则 */
    public static List<Rule> all() {
        return List.of(
                new LineTooLongRule(),
                new TrailingWhitespaceRule(),
                new MissingFinalNewlineRule());
    }

    /** 除 Markdown 外的全部可扫描语言 */
    private static final Set<Language> NON_MARKDOWN = Collections.unmodifiableSet(
            EnumSet.complementOf(EnumSet.of(Language.UNKNOWN, Language.MARKDOWN)));

    // ==================================================================
    // 1. 单行超长
    // ==================================================================
    static final class LineTooLongRule extends AbstractTextRule {

        /**
         * 连续 40 个以上**可打印 ASCII** 非空白字符
         * <p>
         * 这类「断开就坏掉」的整块内容不该按行宽要求折行：URL、base64、哈希、
         * 压缩后的产物都属于此类，它们同时也是超长行告警的主要来源。
         * <p>
         * 限定 ASCII 是关键：中文行文本来就不含空格，用 {@code \\S{40,}} 会把
         * 每一行较长的中文说明都当成「不可断开的内容」而全部放过。
         */
        private static final Pattern UNBREAKABLE_TOKEN = Pattern.compile("[!-~]{40,}");

        LineTooLongRule() {
            super("STYLE.LINE_TOO_LONG", "单行过长", IssueType.STYLE,
                    Severity.MINOR, 1.0,
                    "单行超出长度上限，横向滚动阅读成本高，也无法在并排对比的 diff 里完整显示（长度上限可在阈值中调整）",
                    NON_MARKDOWN);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int maxLength = ctx.thresholds().getMaxLineLength();
            List<String> lines = lines(ctx);
            List<Issue> issues = new ArrayList<>();
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = lines.get(index);
                if (line.length() <= maxLength || UNBREAKABLE_TOKEN.matcher(line).find()) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "该行长度 " + line.length() + " 字符，超过上限 " + maxLength,
                        "按语义拆行；若确实无法拆分（如内嵌的长字符串），可调大 maxLineLength 阈值"));
            }
            return issues;
        }
    }

    // ==================================================================
    // 2. 行尾空白
    // ==================================================================
    static final class TrailingWhitespaceRule extends AbstractTextRule {

        private static final Pattern TRAILING = Pattern.compile("[ \t]+$");

        TrailingWhitespaceRule() {
            super("STYLE.TRAILING_WHITESPACE", "行尾有多余空白", IssueType.STYLE,
                    Severity.MINOR, 1.0,
                    "行尾残留空格或制表符，会让 diff 出现无意义的变更，也是各类空白检查工具的常见报错",
                    NON_MARKDOWN);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> lines = lines(ctx);
            List<Issue> issues = new ArrayList<>();
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = lines.get(index);
                // 整行都是空白的行跳过：编辑器给空行自动补缩进极为常见，逐行报告会立刻刷屏，
                // 而它带来的阅读与 diff 成本远低于「代码行尾部多敲了空格」
                if (line.isBlank() || !TRAILING.matcher(line).find()) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "行尾存在多余空白",
                        "删除行尾空白；可在编辑器里开启「保存时去除行尾空白」"));
            }
            return issues;
        }
    }

    // ==================================================================
    // 3. 文件末尾缺换行
    // ==================================================================
    static final class MissingFinalNewlineRule extends AbstractTextRule {

        MissingFinalNewlineRule() {
            super("STYLE.MISSING_FINAL_NEWLINE", "文件末尾缺少换行", IssueType.STYLE,
                    Severity.MINOR, 1.0,
                    "文件末尾没有换行符，POSIX 文本文件应以换行结尾，否则 diff 会把最后一行与下一行并在一起显示",
                    Language.scannable());
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            String source = ctx.parsed().getSource();
            // 空文件不报：没有内容也就谈不上「末尾缺换行」
            if (source == null || source.isEmpty() || source.endsWith("\n")) {
                return List.of();
            }
            return List.of(issueAtLine(ctx, Math.max(ctx.lineCount(), 1),
                    "文件末尾缺少换行符",
                    "在文件末尾补一个换行；多数编辑器可在设置中开启「文件末尾自动插入换行」"));
        }
    }
}
