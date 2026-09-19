package com.cq.rule.performance;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.parser.AstScopeUtils;
import com.cq.rule.AbstractRule;
import com.cq.rule.LoopCallRule;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 性能维度规则（10 条）
 * <p>
 * 六条「循环内的 X」规则继承 {@link LoopCallRule}，共享循环发现与延迟作用域排除。
 * 延迟排除至关重要：{@code list.forEach(x -> dao.find(x))} 里的调用在语法上位于循环体内，
 * 但它是回调而非每轮迭代执行，不排除会造成大规模误报。
 */
public final class PerformanceRules {

    private PerformanceRules() {}

    /** 全部性能规则 */
    public static List<Rule> all() {
        return List.of(
                new DbCallInLoopRule(),
                new StringConcatInLoopRule(),
                new LogConcatRule(),
                new PatternCompileInLoopRule(),
                new ContainsInLoopRule(),
                new NewStringRule(),
                new SleepInLoopRule(),
                new DateFormatInLoopRule(),
                new CollectionCopyInLoopRule(),
                new StringFormatInLoopRule());
    }

    /** 数据访问对象的字段名/变量名特征 */
    private static final Pattern DB_SCOPE = Pattern.compile(
            "(?i).*(repo|repository|dao|mapper|jdbc|entitymanager|session|conn|stmt|statement|template).*");

    /** 数据访问方法名特征 */
    private static final Pattern DB_METHOD = Pattern.compile(
            "(?i)(find|load|query|select|save|insert|update|delete|persist|flush|count|exists|fetch).*");

    /** 日志方法名 */
    private static final List<String> LOG_METHODS = List.of(
            "trace", "debug", "info", "warn", "error", "fatal");

    // ------------------------------------------------------------------
    // 1. 循环内查库
    // ------------------------------------------------------------------
    static final class DbCallInLoopRule extends LoopCallRule {

        DbCallInLoopRule() {
            super("PERF.DB_IN_LOOP", "循环内查询数据库", IssueType.PERFORMANCE,
                    Severity.CRITICAL, 0.65, "在循环中逐条访问数据库会产生 N+1 查询，应改为批量查询");
        }

        @Override
        protected boolean matches(MethodCallExpr call, RuleContext ctx) {
            String name = call.getNameAsString();
            Expression scope = call.getScope().orElse(null);
            if (scope == null) {
                return false;
            }
            // 内存容器（List/Map/Optional）不是数据库，必须排除，否则 for 循环里
            // 每次 list.get(i) / map.get(k) 都会被误报成查库
            if (ctx.types().isInMemoryCollection(scope)) {
                return false;
            }
            // 无歧义的 ORM 派生查询方法名，如 findByUserId
            if (name.startsWith("findBy") || name.equals("selectOne") || name.equals("selectList")
                    || name.equals("selectCount") || name.equals("saveAll") || name.equals("queryForList")) {
                return true;
            }
            boolean dbScope = DB_SCOPE.matcher(scope.toString()).matches();
            return dbScope && DB_METHOD.matcher(name).matches() && !name.startsWith("get") && !name.startsWith("is");
        }

        @Override
        protected String message(MethodCallExpr call, RuleContext ctx) {
            return "循环内调用 " + call.getNameAsString() + " 访问数据库，形成 N+1 查询";
        }

        @Override
        protected String suggestion(MethodCallExpr call, RuleContext ctx) {
            return "把查询提到循环外批量执行（IN 查询或批量接口），再在内存中匹配";
        }
    }

    // ------------------------------------------------------------------
    // 2. 循环内字符串拼接
    // ------------------------------------------------------------------
    static final class StringConcatInLoopRule extends AbstractRule {

        StringConcatInLoopRule() {
            super("PERF.STRING_CONCAT_IN_LOOP", "循环内字符串拼接", IssueType.PERFORMANCE,
                    Severity.MAJOR, 0.7, "循环内用 + 拼接字符串每轮都新建对象，应改用 StringBuilder");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (AssignExpr assign : ctx.findAll(AssignExpr.class)) {
                if (assign.getOperator() != AssignExpr.Operator.PLUS) {
                    continue;
                }
                // 类型必须可判定为 String，否则会把合法数值累加也报出来
                if (!ctx.types().isType(assign.getTarget(), "String")) {
                    continue;
                }
                Node loop = AstScopeUtils.enclosingLoop(assign).orElse(null);
                if (loop == null || AstScopeUtils.isDeferredWithin(assign, loop)) {
                    continue;
                }
                issues.add(issue(ctx, assign,
                        "循环内使用 += 拼接字符串，每轮迭代都会创建新的字符串对象",
                        "把拼接移到循环外，或改用 StringBuilder 并在循环外声明"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 3. 日志拼接（应使用占位符）
    // ------------------------------------------------------------------
    static final class LogConcatRule extends AbstractRule {

        LogConcatRule() {
            super("PERF.LOG_CONCAT", "日志未使用占位符", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.85, "日志参数用字符串拼接时，即使日志级别未开启也会执行拼接");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!LOG_METHODS.contains(call.getNameAsString())) {
                    continue;
                }
                boolean loggerScope = call.getScope()
                        .map(scope -> scope.toString().toLowerCase(Locale.ROOT).contains("log"))
                        .orElse(false);
                if (!loggerScope) {
                    continue;
                }
                for (Expression argument : call.getArguments()) {
                    if (argument instanceof BinaryExpr binary
                            && binary.getOperator() == BinaryExpr.Operator.PLUS
                            && hasNonLiteralOperand(binary)) {
                        issues.add(issue(ctx, call,
                                "日志参数使用字符串拼接，即使该日志级别未开启也会执行拼接",
                                "改用占位符：log.info(\"value={}\", value)"));
                        break;
                    }
                }
            }
            return issues;
        }

        /** 拼接中是否含非字面量操作数（全为字面量时编译期已折叠，无害） */
        private static boolean hasNonLiteralOperand(BinaryExpr binary) {
            return !(binary.getLeft() instanceof StringLiteralExpr
                    && binary.getRight() instanceof StringLiteralExpr);
        }
    }

    // ------------------------------------------------------------------
    // 4. 循环内编译正则
    // ------------------------------------------------------------------
    static final class PatternCompileInLoopRule extends LoopCallRule {

        PatternCompileInLoopRule() {
            super("PERF.PATTERN_COMPILE_IN_LOOP", "循环内编译正则", IssueType.PERFORMANCE,
                    Severity.MAJOR, 0.85, "Pattern.compile 开销较大，放在循环内会重复编译同一正则");
        }

        @Override
        protected boolean matches(MethodCallExpr call, RuleContext ctx) {
            return call.getNameAsString().equals("compile")
                    && call.getScope().map(scope -> typeNameIs(scope.toString(), "Pattern")).orElse(false);
        }

        @Override
        protected String message(MethodCallExpr call, RuleContext ctx) {
            return "循环内调用 Pattern.compile 重复编译正则表达式";
        }

        @Override
        protected String suggestion(MethodCallExpr call, RuleContext ctx) {
            return "把 Pattern 定义为 static final 常量，在循环外编译一次";
        }
    }

    // ------------------------------------------------------------------
    // 5. 循环内 List.contains（O(n²)）
    // ------------------------------------------------------------------
    static final class ContainsInLoopRule extends LoopCallRule {

        ContainsInLoopRule() {
            super("PERF.CONTAINS_IN_LOOP", "循环内 List.contains", IssueType.PERFORMANCE,
                    Severity.MAJOR, 0.7, "List.contains 是 O(n)，嵌套在循环中形成 O(n²)，应改用 Set");
        }

        @Override
        protected boolean matches(MethodCallExpr call, RuleContext ctx) {
            if (!call.getNameAsString().equals("contains") || call.getArguments().size() != 1) {
                return false;
            }
            // 必须判定为 List：String.contains 是另一个语义，Set/Map 查询是 O(1)
            return ctx.types().isList(call.getScope().orElse(null));
        }

        @Override
        protected String message(MethodCallExpr call, RuleContext ctx) {
            return "循环内调用 List.contains，整体复杂度 O(n²)";
        }

        @Override
        protected String suggestion(MethodCallExpr call, RuleContext ctx) {
            return "改用 HashSet 做存在性判断（O(1)）";
        }
    }

    // ------------------------------------------------------------------
    // 6. new String(...)
    // ------------------------------------------------------------------
    static final class NewStringRule extends AbstractRule {

        NewStringRule() {
            super("PERF.NEW_STRING", "多余的 String 构造", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.95, "new String(\"...\") 会额外创建一个对象，直接赋值即可");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (!typeNameIs(creation.getTypeAsString(), "String")) {
                    continue;
                }
                // 仅匹配 new String("字面量")；new String(bytes, charset) 是合法用法
                if (creation.getArguments().size() != 1
                        || !(creation.getArgument(0) instanceof StringLiteralExpr)) {
                    continue;
                }
                issues.add(issue(ctx, creation,
                        "使用 new String(\"...\") 创建了多余的对象",
                        "直接写字符串字面量即可"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 7. 循环内 sleep
    // ------------------------------------------------------------------
    static final class SleepInLoopRule extends LoopCallRule {

        SleepInLoopRule() {
            super("PERF.SLEEP_IN_LOOP", "循环内休眠", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.6, "循环内 Thread.sleep 多为轮询等待，累计耗时不可控");
        }

        @Override
        protected boolean matches(MethodCallExpr call, RuleContext ctx) {
            if (!call.getNameAsString().equals("sleep")) {
                return false;
            }
            String scope = call.getScope().map(Object::toString).orElse("");
            return typeNameIs(scope, "Thread") || typeNameIs(scope, "TimeUnit")
                    || scope.matches(".*\\.(SECONDS|MILLISECONDS|MINUTES|NANOSECONDS|MICROSECONDS|HOURS)");
        }

        @Override
        protected String message(MethodCallExpr call, RuleContext ctx) {
            return "循环内调用 sleep 休眠";
        }

        @Override
        protected String suggestion(MethodCallExpr call, RuleContext ctx) {
            return "改用条件变量、CountDownLatch 或带超时的等待，避免忙轮询";
        }
    }

    // ------------------------------------------------------------------
    // 8. 循环内创建 SimpleDateFormat
    // ------------------------------------------------------------------
    static final class DateFormatInLoopRule extends AbstractRule {

        DateFormatInLoopRule() {
            super("PERF.DATE_FORMAT_IN_LOOP", "循环内创建日期格式化器", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.8, "SimpleDateFormat 构造开销大，放在循环内会重复创建");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (!typeNameIs(creation.getTypeAsString(), "SimpleDateFormat")) {
                    continue;
                }
                Node loop = AstScopeUtils.enclosingLoop(creation).orElse(null);
                if (loop == null || AstScopeUtils.isDeferredWithin(creation, loop)) {
                    continue;
                }
                issues.add(issue(ctx, creation,
                        "循环内创建 SimpleDateFormat，每轮迭代都重复构造",
                        "提到循环外复用，或改用线程安全的 DateTimeFormatter"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 9. 循环内集合拷贝
    // ------------------------------------------------------------------
    static final class CollectionCopyInLoopRule extends AbstractRule {

        CollectionCopyInLoopRule() {
            super("PERF.COLLECTION_COPY_IN_LOOP", "循环内拷贝集合", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.5, "循环内整体拷贝集合会放大内存与时间开销");
        }

        private static final List<String> COLLECTIONS = List.of(
                "ArrayList", "LinkedList", "HashMap", "HashSet", "TreeMap", "TreeSet", "LinkedHashMap");

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (ObjectCreationExpr creation : ctx.findAll(ObjectCreationExpr.class)) {
                if (!typeNameIs(creation.getTypeAsString(), COLLECTIONS.toArray(new String[0]))
                        || creation.getArguments().size() != 1) {
                    continue;
                }
                Expression argument = creation.getArgument(0);
                if (!ctx.types().isInMemoryCollection(argument) && !argument.isMethodCallExpr()) {
                    continue;
                }
                Node loop = AstScopeUtils.enclosingLoop(creation).orElse(null);
                if (loop == null || AstScopeUtils.isDeferredWithin(creation, loop)) {
                    continue;
                }
                issues.add(issue(ctx, creation,
                        "循环内拷贝集合 " + creation.getTypeAsString() + "(...)",
                        "把拷贝提到循环外，或只保留必要元素"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 10. 循环内 String.format
    // ------------------------------------------------------------------
    static final class StringFormatInLoopRule extends LoopCallRule {

        StringFormatInLoopRule() {
            super("PERF.STRING_FORMAT_IN_LOOP", "循环内格式化字符串", IssueType.PERFORMANCE,
                    Severity.MINOR, 0.7, "String.format 内部需解析格式串并创建 Formatter，循环内开销放大");
        }

        @Override
        protected boolean matches(MethodCallExpr call, RuleContext ctx) {
            return call.getNameAsString().equals("format")
                    && call.getScope().map(scope -> typeNameIs(scope.toString(), "String")).orElse(false);
        }

        @Override
        protected String message(MethodCallExpr call, RuleContext ctx) {
            return "循环内调用 String.format 反复解析格式串";
        }

        @Override
        protected String suggestion(MethodCallExpr call, RuleContext ctx) {
            return "复用同一个 Formatter，或改用 StringBuilder 手工拼接";
        }
    }
}
