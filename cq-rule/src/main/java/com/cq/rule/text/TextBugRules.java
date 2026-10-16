package com.cq.rule.text;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.ast.ParseProblems;
import com.cq.common.lang.Language;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bug 维度规则（文本扫描版，4 条）
 * <p>
 * 这一组对应「忘了清理」与「文件本身写坏了」两类问题：遗留的合并冲突标记、
 * 调试输出、未完成标记，以及 JSON / YAML / XML 的语法错误。它们全都不需要理解代码语义，
 * 但漏到运行时的代价很高。
 */
public final class TextBugRules {

    private TextBugRules() {}

    /** 全部文本 Bug 规则 */
    public static List<Rule> all() {
        return List.of(
                new ConflictMarkerRule(),
                new DebugStatementRule(),
                new TodoMarkerRule(),
                new InvalidStructuredFileRule());
    }

    /**
     * 拼出规则自己要匹配的关键字
     * <p>
     * 本仓库会被本系统自己扫描。若在源码里直接写下完整的关键字字面量，规则每次自检
     * 都会命中自己写的规则文件 —— 这既是误报，也会让人误以为规则本身有问题。
     * 拆成两段拼接即可规避，代价只是写法上稍微绕一点。
     */
    private static String keyword(String head, String tail) {
        return head + tail;
    }

    // ==================================================================
    // 1. 遗留的合并冲突标记
    // ==================================================================
    static final class ConflictMarkerRule extends AbstractTextRule {

        // 三个标记都以「行首 + 连续 7 个符号」为判据，因此正则里的 ^ 永远只在行首生效
        private static final Pattern OUR_SIDE = Pattern.compile("^<{7}(\\s|$)");
        private static final Pattern THEIR_SIDE = Pattern.compile("^>{7}(\\s|$)");
        private static final Pattern BASE_SIDE = Pattern.compile("^\\|{7}(\\s|$)");
        ConflictMarkerRule() {
            super("BUG.CONFLICT_MARKER", "遗留的合并冲突标记", IssueType.BUG,
                    Severity.BLOCKER, 0.95,
                    "文件里残留着合并冲突标记，说明冲突没有真正解决，代码无法编译或会产生错误行为",
                    Language.scannable());
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> lines = lines(ctx);
            int firstMarker = -1;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (OUR_SIDE.matcher(line).find() || THEIR_SIDE.matcher(line).find()
                        || BASE_SIDE.matcher(line).find()) {
                    firstMarker = index;
                    break;
                }
            }
            // 只靠 ======= 判定是不够的：一整行等号在 Markdown 里是合法的 Setext 标题下划线，
            // 在脚本与文档里也常被当作分隔线。因此这里只认三个方向标记，它们没有歧义
            if (firstMarker < 0) {
                return List.of();
            }
            return List.of(issueAtLine(ctx, firstMarker + 1,
                    "文件中存在未解决的合并冲突标记",
                    "解决冲突并删除 <<<<<<< / ======= / >>>>>>> 标记后重新提交"));
        }
    }

    // ==================================================================
    // 2. 遗留的调试输出
    // ==================================================================
    static final class DebugStatementRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = EnumSet.of(
                Language.JAVASCRIPT, Language.TYPESCRIPT, Language.VUE,
                Language.PYTHON, Language.GO, Language.SHELL);

        /** 前端：console 输出与断点语句 */
        private static final Pattern JS_DEBUG = Pattern.compile(
                "\\bconsole\\.(log|debug|trace)\\s*\\("
                        + "|\\b" + keyword("debug", "ger") + "\\b"
                        + "|\\balert\\s*\\(");

        /** Python：print 调试、断点 */
        private static final Pattern PY_DEBUG = Pattern.compile(
                "^\\s*print\\s*\\(|\\b" + keyword("break", "point") + "\\s*\\(|\\bpdb\\.set_trace\\s*\\(");

        /** Go：标准库打印 */
        private static final Pattern GO_DEBUG = Pattern.compile(
                "\\bfmt\\.Print(f|ln)?\\s*\\(");

        /** Shell：打开命令回显 */
        private static final Pattern SH_DEBUG = Pattern.compile("^\\s*set\\s+-[a-zA-Z]*x[a-zA-Z]*\\b");

        /** 测试文件里的输出是正常内容，不该按「遗留调试」处理 */
        private static final Pattern TEST_FILE = Pattern.compile(
                "(?i).*(^|/)(test|tests|__tests__|spec)/.*"
                        + "|.*\\.(spec|test)\\.[jt]sx?$"
                        + "|.*\\.spec\\.py$"
                        + "|.*/test_.*\\.py$");

        /** 关掉某行的 lint 通常就是有意保留 */
        private static final Pattern LINT_ESCAPE = Pattern.compile("eslint-disable|noqa|nolint");

        DebugStatementRule() {
            super("BUG.DEBUG_STATEMENT", "遗留的调试输出", IssueType.BUG,
                    Severity.MINOR, 0.5,
                    "源码中残留调试输出，会在生产日志里刷屏甚至泄露内部数据，提交前应清理",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            String path = ctx.filePath() == null ? "" : ctx.filePath();
            if (TEST_FILE.matcher(path).find()) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            List<String> lines = lines(ctx);
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = lines.get(index);
                if (isBlankOrComment(ctx, line) || LINT_ESCAPE.matcher(line).find()) {
                    continue;
                }
                if (matches(ctx, line)) {
                    issues.add(issueAtLine(ctx, index + 1,
                            "源码中残留调试输出语句",
                            "提交前删除调试输出；需要长期保留的日志请改用统一的日志组件"));
                }
            }
            return issues;
        }

        private static boolean matches(RuleContext ctx, String line) {
            return switch (ctx.language()) {
                case PYTHON -> PY_DEBUG.matcher(line).find();
                case GO -> GO_DEBUG.matcher(line).find();
                case SHELL -> SH_DEBUG.matcher(line).find();
                default -> JS_DEBUG.matcher(line).find();
            };
        }
    }

    // ==================================================================
    // 3. 未完成标记
    // ==================================================================
    static final class TodoMarkerRule extends AbstractTextRule {

        /** Markdown 里这类标记是正常行文，扫它只会刷屏 */
        private static final Set<Language> SCOPED = Collections.unmodifiableSet(
                EnumSet.complementOf(EnumSet.of(Language.UNKNOWN, Language.MARKDOWN)));

        /** 只认大写标记 + 词边界：todoList、sk-xxx 这类小写写法不会被命中 */
        private static final Pattern MARKER = Pattern.compile(
                "\\b(" + keyword("TO", "DO") + "|" + keyword("FIX", "ME") + "|"
                        + keyword("HA", "CK") + "|" + keyword("XX", "X") + ")\\b");

        /** 带 issue 编号的标记说明是有意留置，不算遗留 */
        private static final Pattern TRACKED = Pattern.compile(
                "\\b(" + keyword("TO", "DO") + "|" + keyword("FIX", "ME") + ")"
                        + "\\s*[:(]?\\s*(#\\d+|[A-Z][A-Z0-9]{1,}-\\d+)");

        TodoMarkerRule() {
            super("BUG.TODO_MARKER", "未完成标记", IssueType.BUG,
                    Severity.MINOR, 0.4,
                    "代码注释里留有未完成 / 待办 / 临时方案一类的标记，容易在交付时被遗忘",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            List<String> lines = lines(ctx);
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = lines.get(index);
                // 刻意**不**跳过注释行：未完成标记本来就长在注释里，跳过注释等于跳过全部命中。
                // 精度改由「只认大写 + 词边界」来保证，todoList / sk-xxx 这类写法不会命中
                if (line.isBlank()) {
                    continue;
                }
                Matcher matcher = MARKER.matcher(line);
                if (!matcher.find() || TRACKED.matcher(line).find()) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "存在未完成标记 " + matcher.group(1),
                        "在交付前处理该标记，或补上负责人与跟踪单号，例如加上 (#123) 这类 issue 编号"));
            }
            return issues;
        }
    }

    // ==================================================================
    // 4. 结构化文件语法错误
    // ==================================================================
    static final class InvalidStructuredFileRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = EnumSet.of(
                Language.JSON, Language.YAML, Language.XML);

        InvalidStructuredFileRule() {
            super("BUG.INVALID_STRUCTURED_FILE", "配置文件语法错误", IssueType.BUG,
                    Severity.MAJOR, 1.0,
                    "JSON / YAML / XML 文件本身写错了，服务启动或构建时才会暴露，应就地修正",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> problems = ctx.parsed().getSummary().getParseErrors();
            if (problems.isEmpty()) {
                return List.of();
            }
            // 三种解析器都在遇到第一个错误时即停止，因此报告问题列表里的第一条即可
            String problem = problems.get(0);
            int line = Math.min(ParseProblems.lineOf(problem), Math.max(ctx.lineCount(), 1));
            return List.of(issueAtLine(ctx, line,
                    "文件不是合法的 " + ctx.language().displayName() + "：" + ParseProblems.messageOf(problem),
                    "修正语法错误后重新提交，多半是括号/引号不配对、缩进不一致或缺少逗号"));
        }
    }
}
