package com.cq.rule.text;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.lang.Language;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 性能维度规则（文本扫描版，3 条）
 * <p>
 * 三条都围绕 SQL。SQL 是「性能问题写在文本里、且判定规则极其确定」的少数场景之一：
 * 全表扫描、无法命中索引、误伤全表写操作，都不需要执行计划就能判定。
 * <p>
 * 语言范围上，{@code SELECT *} 同时看 {@code .sql} 与 MyBatis 映射文件（{@code .xml}），
 * 另外两条只作用于 {@code .sql}：MyBatis 里语句跨标签、还有实体转义，
 * 要正确切分语句的代价与收益不成比例。
 */
public final class TextPerformanceRules {

    private TextPerformanceRules() {}

    /** 全部文本性能规则 */
    public static List<Rule> all() {
        return List.of(
                new SqlSelectStarRule(),
                new SqlWriteWithoutWhereRule(),
                new SqlLeadingWildcardRule());
    }

    /** 迁移 / 初始化脚本：整表重写是它们的本意 */
    private static final Pattern SEED_SCRIPT = Pattern.compile(
            "(?i).*(/migration/|/migrations/|/flyway/|/liquibase/|/changelog/|/db/migration/"
                    + "|/(V|R)\\d+__.*\\.sql$|data\\.sql$|init.*\\.sql$|seed.*\\.sql$|schema\\.sql$)");

    // ==================================================================
    // 1. SELECT *
    // ==================================================================
    static final class SqlSelectStarRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = EnumSet.of(Language.SQL, Language.XML);

        /**
         * SELECT 后紧跟星号
         * <p>
         * {@code count(*)} 不会命中：它与 {@code select} 之间隔着 {@code count(}。
         * 要求星号后面是空白或逗号，避免把 {@code select *} 出现在字符串里的情况也算进来。
         */
        private static final Pattern SELECT_STAR = Pattern.compile("(?i)\\bselect\\s+\\*(\\s|,|$)");

        SqlSelectStarRule() {
            super("PERF.SQL_SELECT_STAR", "查询使用 SELECT *", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.6,
                    "SELECT * 会返回全部列，既放大网络与内存开销，也让新增列悄悄改变结果集，应显式列出需要的列",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> lines = lines(ctx);
            // XML 只在 MyBatis 映射文件里看 SQL：普通数据 XML 里出现 select * 多半只是文本内容
            if (ctx.language() == Language.XML && !isMybatisMapper(ctx)) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = stripInlineComment(ctx, lines.get(index));
                if (isBlankOrComment(ctx, line) || !SELECT_STAR.matcher(line).find()) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "查询语句使用了 SELECT *",
                        "显式列出需要的列：既减少数据传输，也避免表结构变更后结果集悄然改变"));
            }
            return issues;
        }

        /** 文件是否像 MyBatis 映射文件 */
        private static boolean isMybatisMapper(RuleContext ctx) {
            String source = ctx.parsed().getSource();
            return source != null && source.contains("<mapper");
        }
    }

    // ==================================================================
    // 2. 没有 WHERE 的写操作
    // ==================================================================
    static final class SqlWriteWithoutWhereRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = Set.of(Language.SQL);

        SqlWriteWithoutWhereRule() {
            super("PERF.SQL_WRITE_WITHOUT_WHERE", "写操作缺少 WHERE 条件", IssueType.PERFORMANCE,
                    Severity.CRITICAL, 0.7,
                    "UPDATE / DELETE 没有 WHERE 条件，会写满整张表，多数情况下是漏写而非本意",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            // 迁移与初始化脚本整表重写是有意为之，不算问题
            if (SEED_SCRIPT.matcher(ctx.filePath() == null ? "" : ctx.filePath()).matches()) {
                return List.of();
            }
            String source = ctx.parsed().getSource();
            List<Issue> issues = new ArrayList<>();
            for (SqlStatements.Statement statement : SqlStatements.split(source)) {
                if (capped(issues)) {
                    break;
                }
                String verb = statement.verb();
                if ((!verb.equals("UPDATE") && !verb.equals("DELETE")) || statement.hasWhere()) {
                    continue;
                }
                // 跨行语句的起始行已经由切分器给出；取不到时退回第 1 行，至少不会丢
                int line = statement.startLine() < 0 ? 1 : statement.startLine();
                issues.add(issueAtLine(ctx, line,
                        verb + " 语句没有任何 WHERE 条件，会作用于全表",
                        "补上 WHERE 条件；确实需要全表写操作时，在语句上方注明原因并加入忽略列表"));
            }
            return issues;
        }
    }

    // ==================================================================
    // 3. LIKE 前导通配符
    // ==================================================================
    static final class SqlLeadingWildcardRule extends AbstractTextRule {

        private static final Set<Language> SCOPED = Set.of(Language.SQL);

        /** LIKE 的模式以 % 开头，或由 CONCAT('%', …) 拼出 */
        private static final Pattern LEADING_WILDCARD = Pattern.compile(
                "(?i)\\blike\\s+['\"]\\s*%|\\blike\\s+concat\\s*\\(\\s*['\"]\\s*%");

        /** 只匹配一个 % 表示「任意」，不含前导内容，不算索引问题 */
        private static final Pattern MATCH_ALL = Pattern.compile("(?i)\\blike\\s+['\"]\\s*%\\s*['\"]");

        SqlLeadingWildcardRule() {
            super("PERF.SQL_LEADING_WILDCARD", "LIKE 使用前导通配符", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.6,
                    "LIKE 以 % 开头无法命中 B-Tree 索引，会退化为全表扫描；数据量大时应改用全文索引或搜索引擎",
                    SCOPED);
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> lines = lines(ctx);
            List<Issue> issues = new ArrayList<>();
            for (int index = 0; index < lines.size() && !capped(issues); index++) {
                String line = stripInlineComment(ctx, lines.get(index));
                if (isBlankOrComment(ctx, line)
                        || !LEADING_WILDCARD.matcher(line).find()
                        || MATCH_ALL.matcher(line).find()) {
                    continue;
                }
                issues.add(issueAtLine(ctx, index + 1,
                        "LIKE 模式以 % 开头，无法使用索引",
                        "改为后置通配（LIKE 'foo%'）或使用全文索引 / 搜索引擎处理模糊前缀查询"));
            }
            return issues;
        }
    }
}
