package com.cq.rule.bug;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.parser.AstScopeUtils;
import com.cq.rule.AbstractRule;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Bug 维度规则（10 条）
 * <p>
 * 这一组是全部四组里信噪比最高的：命中基本都是真实缺陷，因此多数规则给到
 * MAJOR 及以上严重级。规则实现均为无状态，可安全并发调用。
 */
public final class BugRules {

    private BugRules() {}

    /** 全部 Bug 规则 */
    public static List<Rule> all() {
        return List.of(
                new EmptyCatchRule(),
                new PrintStackTraceRule(),
                new ReturnInFinallyRule(),
                new ResourceLeakRule(),
                new StringEqualityRule(),
                new BigDecimalEqualsRule(),
                new OptionalGetRule(),
                new StaticDateFormatRule(),
                new SelfComparisonRule(),
                new DivideByZeroRule());
    }

    // ------------------------------------------------------------------
    // 1. 空 catch 块
    // ------------------------------------------------------------------
    static final class EmptyCatchRule extends AbstractRule {

        EmptyCatchRule() {
            super("BUG.EMPTY_CATCH", "空 catch 块吞异常", IssueType.BUG,
                    Severity.MAJOR, 0.9, "catch 块为空会静默吞掉异常，导致故障难以定位");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (CatchClause clause : ctx.findAll(CatchClause.class)) {
                if (!clause.getBody().getStatements().isEmpty() || hasComment(clause, ctx)) {
                    continue;
                }
                String caught = clause.getParameter().getTypeAsString();
                boolean broad = caught.equals("Exception") || caught.equals("Throwable")
                        || caught.equals("RuntimeException");
                issues.add(issue(ctx, clause,
                        "catch 块为空，异常被静默吞掉：catch (" + caught + " "
                                + clause.getParameter().getNameAsString() + ")",
                        "至少记录日志，或保留注释说明为何可以安全忽略该异常",
                        broad ? Severity.CRITICAL : Severity.MAJOR, null));
            }
            return issues;
        }

        /**
         * 块内是否有注释
         * <p>
         * {@code catch (IOException e) { /* 文件不存在时按空处理 *&#47; }} 是有意为之的写法，
         * 不应与「忘记处理」混为一谈。AST 注释在极端情况下可能缺失，故再用源码文本兜底。
         */
        private static boolean hasComment(CatchClause clause, RuleContext ctx) {
            if (clause.getBody().getAllContainedComments().size() > 0) {
                return true;
            }
            return clause.getBody().getRange()
                    .map(range -> {
                        for (int line = range.begin.line; line <= range.end.line; line++) {
                            String text = ctx.lineAt(line);
                            if (text.contains("//") || text.contains("/*")) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .orElse(false);
        }
    }

    // ------------------------------------------------------------------
    // 2. catch 中仅 printStackTrace
    // ------------------------------------------------------------------
    static final class PrintStackTraceRule extends AbstractRule {

        PrintStackTraceRule() {
            super("BUG.PRINT_STACK_TRACE", "异常仅打印堆栈未记录日志", IssueType.BUG,
                    Severity.MINOR, 0.85, "printStackTrace 输出到标准错误，生产环境无法被日志系统采集");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (CatchClause clause : ctx.findAll(CatchClause.class)) {
                MethodCallExpr printStackTrace = onlyPrintStackTrace(clause);
                if (printStackTrace == null) {
                    continue;
                }
                // 报告在 printStackTrace 调用本身而非 catch 头：
                // 前者才是真正要改的那一行，行号与代码片段都更准确，
                // 下游的确定性修复模板也依赖片段里能匹配到该调用
                issues.add(issue(ctx, printStackTrace,
                        "catch 块中仅调用 printStackTrace，异常信息不会进入日志系统",
                        "改用日志框架记录，例如 log.error(\"处理失败\", e)"));
            }
            return issues;
        }

        /**
         * 若 catch 块中仅有一句 {@code e.printStackTrace()}，返回该调用节点，否则返回 null
         */
        private static MethodCallExpr onlyPrintStackTrace(CatchClause clause) {
            String caught = clause.getParameter().getNameAsString();
            List<com.github.javaparser.ast.stmt.Statement> statements = clause.getBody().getStatements();
            if (statements.size() != 1 || !(statements.get(0) instanceof ExpressionStmt exprStmt)) {
                return null;
            }
            if (!(exprStmt.getExpression() instanceof MethodCallExpr call)) {
                return null;
            }
            if (!call.getNameAsString().equals("printStackTrace")) {
                return null;
            }
            // 只认 e.printStackTrace()，避免把无关对象的同名方法算进来
            boolean onCaughtException = call.getScope()
                    .map(scope -> scope.toString().equals(caught))
                    .orElse(false);
            return onCaughtException ? call : null;
        }
    }

    // ------------------------------------------------------------------
    // 3. finally 中的 return
    // ------------------------------------------------------------------
    static final class ReturnInFinallyRule extends AbstractRule {

        ReturnInFinallyRule() {
            super("BUG.RETURN_IN_FINALLY", "finally 块中返回", IssueType.BUG,
                    Severity.MAJOR, 0.9, "finally 中的 return 会覆盖 try 中的返回值，并吞掉异常");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (TryStmt tryStmt : ctx.findAll(TryStmt.class)) {
                tryStmt.getFinallyBlock().ifPresent(finallyBlock -> {
                    for (ReturnStmt returnStmt : finallyBlock.findAll(ReturnStmt.class)) {
                        // lambda 或嵌套类型中的 return 属于另一作用域，与 finally 无关
                        if (!isDirectlyIn(returnStmt, finallyBlock)) {
                            continue;
                        }
                        issues.add(issue(ctx, returnStmt,
                                "finally 块中 return 会覆盖 try 块的返回值，并丢弃正在抛出的异常",
                                "删除该 return，把结果赋值给局部变量后在 finally 之后返回"));
                    }
                });
            }
            return issues;
        }

        /** return 是否直接位于该块内（未穿越 lambda 或类型声明） */
        private static boolean isDirectlyIn(Node node, Node block) {
            Node current = node.getParentNode().orElse(null);
            while (current != null && current != block) {
                if (current instanceof com.github.javaparser.ast.expr.LambdaExpr
                        || AstScopeUtils.isScopeBoundary(current)
                        || current instanceof CallableDeclaration) {
                    return false;
                }
                current = current.getParentNode().orElse(null);
            }
            return current == block;
        }
    }

    // ------------------------------------------------------------------
    // 4. 资源未关闭
    // ------------------------------------------------------------------
    static final class ResourceLeakRule extends AbstractRule {

        ResourceLeakRule() {
            super("BUG.RESOURCE_LEAK", "资源可能未关闭", IssueType.BUG,
                    Severity.CRITICAL, 0.6, "IO/数据库连接等资源未使用 try-with-resources，异常路径下会泄漏");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (VariableDeclarator variable : ctx.findAll(VariableDeclarator.class)) {
                Expression initializer = variable.getInitializer().orElse(null);
                if (!(initializer instanceof ObjectCreationExpr creation)) {
                    continue;
                }
                if (!ctx.types().isResource(creation)) {
                    continue;
                }
                String name = variable.getNameAsString();
                if (isInTryWithResources(variable) || isHandedOff(variable, name)) {
                    continue;
                }
                issues.add(issue(ctx, variable,
                        "资源 " + name + " 未使用 try-with-resources，异常路径下可能未关闭",
                        "改用 try (" + creation.getTypeAsString() + " " + name + " = ...) { ... }"));
            }
            return issues;
        }

        /** 是否声明在 try-with-resources 中 */
        private static boolean isInTryWithResources(VariableDeclarator variable) {
            return variable.findAncestor(TryStmt.class)
                    .filter(tryStmt -> tryStmt.getResources().stream()
                            .anyMatch(resource -> resource.isAncestorOf(variable)))
                    .isPresent();
        }

        /**
         * 资源是否被「移交」出去
         * <p>
         * 传给别的方法、返回、或存进字段，都说明关闭责任已经转移，
         * 若不加判断会对所有权传递的常见写法大量误报。
         */
        private static boolean isHandedOff(VariableDeclarator variable, String name) {
            CallableDeclaration<?> owner = AstScopeUtils.enclosingCallable(variable).orElse(null);
            if (owner == null) {
                return true;   // 字段初始化，交由字段层面判断，此处不报
            }
            for (MethodCallExpr call : owner.findAll(MethodCallExpr.class)) {
                boolean asArgument = call.getArguments().stream()
                        .anyMatch(argument -> argument.isNameExpr()
                                && argument.asNameExpr().getNameAsString().equals(name));
                if (asArgument) {
                    return true;
                }
            }
            for (ReturnStmt returnStmt : owner.findAll(ReturnStmt.class)) {
                if (returnStmt.getExpression()
                        .map(expr -> expr.isNameExpr() && expr.asNameExpr().getNameAsString().equals(name))
                        .orElse(false)) {
                    return true;
                }
            }
            for (com.github.javaparser.ast.expr.AssignExpr assign : owner.findAll(com.github.javaparser.ast.expr.AssignExpr.class)) {
                if (assign.getTarget() instanceof FieldAccessExpr
                        && assign.getValue().isNameExpr()
                        && assign.getValue().asNameExpr().getNameAsString().equals(name)) {
                    return true;
                }
            }
            for (MethodCallExpr call : owner.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("close")
                        && call.getScope().map(scope -> scope.toString().equals(name)).orElse(false)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 5. 用 == 比较字符串
    // ------------------------------------------------------------------
    static final class StringEqualityRule extends AbstractRule {

        StringEqualityRule() {
            super("BUG.STRING_EQ_OPERATOR", "使用 == 比较字符串", IssueType.BUG,
                    Severity.MAJOR, 0.9, "== 比较的是引用而非内容，应使用 equals");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (BinaryExpr binary : ctx.findAll(BinaryExpr.class)) {
                if (!isEquality(binary)) {
                    continue;
                }
                Expression left = binary.getLeft();
                Expression right = binary.getRight();
                boolean leftLiteral = left instanceof StringLiteralExpr;
                boolean rightLiteral = right instanceof StringLiteralExpr;
                if (leftLiteral && right instanceof NullLiteralExpr
                        || rightLiteral && left instanceof NullLiteralExpr) {
                    continue;   // x == null 是合法比较
                }
                if (leftLiteral || rightLiteral) {
                    issues.add(issue(ctx, binary,
                            "使用 == 比较字符串字面量，比较的是引用而非内容",
                            "改用 equals：" + (leftLiteral ? right : left).toString() + ".equals(...)"));
                } else if (ctx.types().isType(left, "String") && ctx.types().isType(right, "String")) {
                    issues.add(issue(ctx, binary,
                            "使用 == 比较两个 String 变量，比较的是引用而非内容",
                            "改用 equals / Objects.equals",
                            null, 0.7));
                }
            }
            return issues;
        }

        private static boolean isEquality(BinaryExpr binary) {
            return binary.getOperator() == BinaryExpr.Operator.EQUALS
                    || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
        }
    }

    // ------------------------------------------------------------------
    // 6. BigDecimal 用 equals 比较
    // ------------------------------------------------------------------
    static final class BigDecimalEqualsRule extends AbstractRule {

        BigDecimalEqualsRule() {
            super("BUG.BIGDECIMAL_EQUALS", "BigDecimal 使用 equals 比较", IssueType.BUG,
                    Severity.MAJOR, 0.8, "BigDecimal.equals 会同时比较精度，1.0 与 1.00 不相等，应使用 compareTo");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("equals") || call.getArguments().size() != 1) {
                    continue;
                }
                Expression scope = call.getScope().orElse(null);
                if (!ctx.types().isType(scope, "BigDecimal")) {
                    continue;
                }
                issues.add(issue(ctx, call,
                        "BigDecimal 使用 equals 比较，会因精度不同而误判（1.0 与 1.00 不相等）",
                        "改用 compareTo(...) == 0"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 7. Optional.get 未检查
    // ------------------------------------------------------------------
    static final class OptionalGetRule extends AbstractRule {

        OptionalGetRule() {
            super("BUG.OPTIONAL_GET", "Optional.get 未判空", IssueType.BUG,
                    Severity.MAJOR, 0.6, "直接调用 Optional.get 在为空时抛 NoSuchElementException");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (MethodCallExpr call : ctx.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("get") || !call.getArguments().isEmpty()) {
                    continue;
                }
                Expression scope = call.getScope().orElse(null);
                if (!ctx.types().isType(scope, "Optional")) {
                    continue;
                }
                String name = scope.toString();
                CallableDeclaration<?> owner = AstScopeUtils.enclosingCallable(call).orElse(null);
                if (owner != null && hasGuard(owner, name)) {
                    continue;
                }
                issues.add(issue(ctx, call,
                        "Optional.get() 未经 isPresent() 检查，为空时抛出 NoSuchElementException",
                        "改用 orElse / orElseThrow / ifPresent，或先判断 isPresent"));
            }
            return issues;
        }

        /**
         * 同一方法内是否出现过该变量的 isPresent/isEmpty 判断
         * <p>
         * 这是启发式判断，无法精确匹配「提前 return」这类控制流，
         * 因此置信度只给 0.6。
         */
        private static boolean hasGuard(CallableDeclaration<?> owner, String name) {
            for (MethodCallExpr call : owner.findAll(MethodCallExpr.class)) {
                String methodName = call.getNameAsString();
                if (methodName.equals("isPresent") || methodName.equals("isEmpty")) {
                    if (call.getScope().map(scope -> scope.toString().equals(name)).orElse(false)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 8. 静态日期格式化器（线程不安全）
    // ------------------------------------------------------------------
    static final class StaticDateFormatRule extends AbstractRule {

        StaticDateFormatRule() {
            super("BUG.STATIC_DATE_FORMAT", "静态 SimpleDateFormat 字段", IssueType.BUG,
                    Severity.CRITICAL, 0.85, "SimpleDateFormat 非线程安全，作为静态字段被并发调用会得到错误结果");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (FieldDeclaration field : ctx.findAll(FieldDeclaration.class)) {
                if (!field.isStatic()) {
                    continue;
                }
                // 注意：不包含 DateTimeFormatter（它是不可变的，线程安全）
                if (!typeNameIs(field.getElementType().asString(), "SimpleDateFormat")) {
                    continue;
                }
                issues.add(issue(ctx, field,
                        "静态 SimpleDateFormat 字段非线程安全，并发调用会解析出错误日期",
                        "改用 DateTimeFormatter（不可变），或用 ThreadLocal 包装"));
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 9. 自比较
    // ------------------------------------------------------------------
    static final class SelfComparisonRule extends AbstractRule {

        SelfComparisonRule() {
            super("BUG.SELF_COMPARISON", "变量与自身比较", IssueType.BUG,
                    Severity.MAJOR, 0.85, "a == a 恒为真、a != a 恒为假，通常是笔误");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (BinaryExpr binary : ctx.findAll(BinaryExpr.class)) {
                if (binary.getOperator() != BinaryExpr.Operator.EQUALS
                        && binary.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                    continue;
                }
                Expression left = binary.getLeft();
                Expression right = binary.getRight();
                String leftName = nameOf(left);
                if (leftName == null || !leftName.equals(nameOf(right))) {
                    continue;
                }
                // x == x 对浮点数是判断 NaN 的标准写法（NaN != NaN），不能报
                if (ctx.types().isType(left, "double", "float", "Double", "Float")) {
                    continue;
                }
                issues.add(issue(ctx, binary,
                        "变量 " + leftName + " 与自身比较，结果恒定，通常为笔误",
                        "检查两侧是否应为不同变量"));
            }
            return issues;
        }

        private static String nameOf(Expression expression) {
            if (expression instanceof NameExpr nameExpr) {
                return nameExpr.getNameAsString();
            }
            if (expression instanceof FieldAccessExpr fieldAccess) {
                return fieldAccess.toString();
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 10. 常量除零
    // ------------------------------------------------------------------
    static final class DivideByZeroRule extends AbstractRule {

        DivideByZeroRule() {
            super("BUG.DIVIDE_BY_ZERO", "整数除以常量零", IssueType.BUG,
                    Severity.CRITICAL, 0.85, "整数除以 0 抛 ArithmeticException");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<Issue> issues = new ArrayList<>();
            for (BinaryExpr binary : ctx.findAll(BinaryExpr.class)) {
                if (binary.getOperator() != BinaryExpr.Operator.DIVIDE
                        && binary.getOperator() != BinaryExpr.Operator.REMAINDER) {
                    continue;
                }
                if (!isZeroLiteral(binary.getRight())) {
                    continue;
                }
                // x / 0.0 得 Infinity 而非异常，且常是有意为之，必须区分浮点
                String leftType = ctx.types().typeOf(binary.getLeft());
                if (leftType != null && !ctx.types().isIntegral(binary.getLeft())) {
                    continue;
                }
                issues.add(issue(ctx, binary,
                        "整数除以常量 0，运行时会抛 ArithmeticException",
                        "校验除数不为 0，或确认是否应使用浮点运算",
                        null, leftType == null ? 0.6 : 0.95));
            }
            return issues;
        }

        private static boolean isZeroLiteral(Expression expression) {
            if (expression instanceof IntegerLiteralExpr intLiteral) {
                return intLiteral.getValue().replace("_", "").equals("0");
            }
            if (expression instanceof LongLiteralExpr longLiteral) {
                String value = longLiteral.getValue().replace("_", "").replace("L", "").replace("l", "");
                return value.equals("0");
            }
            return false;
        }
    }
}
