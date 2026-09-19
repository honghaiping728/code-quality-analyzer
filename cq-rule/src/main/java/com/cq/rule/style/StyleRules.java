package com.cq.rule.style;

import com.cq.common.Issue;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.ast.ClassInfo;
import com.cq.common.ast.MethodInfo;
import com.cq.common.model.RuleThresholds;
import com.cq.rule.AbstractRule;
import com.cq.rule.Rule;
import com.cq.rule.RuleContext;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 编码规范维度规则（12 条）
 * <p>
 * 这一组阈值全部可配（{@link RuleThresholds}）。其中三条规则**必须**依赖白名单/例外判断，
 * 否则误报率会高到让使用者直接关掉规则引擎：
 * <ul>
 *     <li>常量命名：{@code private static final Logger log} 是 Java 最常见的写法</li>
 *     <li>嵌套深度：{@code else if} 在 JavaParser 中是嵌套的 {@code IfStmt}，
 *         不做识别的话每一级 else-if 阶梯都会命中</li>
 *     <li>缺 Javadoc：{@code @Override} 方法与 getter/setter 应当豁免</li>
 * </ul>
 */
public final class StyleRules {

    private StyleRules() {}

    /** 全部规范规则 */
    public static List<Rule> all() {
        return List.of(
                new MethodTooLongRule(),
                new ComplexityTooHighRule(),
                new ClassTooLongRule(),
                new TooManyParametersRule(),
                new ClassNamingRule(),
                new MethodNamingRule(),
                new ConstantNamingRule(),
                new MagicNumberRule(),
                new MissingJavadocRule(),
                new DeepNestingRule(),
                new UnusedImportRule(),
                new ShortFieldNameRule());
    }

    // ------------------------------------------------------------------
    // 1. 方法过长
    // ------------------------------------------------------------------
    static final class MethodTooLongRule extends AbstractRule {

        MethodTooLongRule() {
            super("STYLE.METHOD_TOO_LONG", "方法过长", IssueType.STYLE,
                    Severity.MAJOR, 1.0, "方法体行数超过阈值，可读性与可测试性下降");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int limit = ctx.thresholds().getMethodMaxLines();
            List<Issue> issues = new ArrayList<>();
            for (MethodDeclaration method : ctx.findAll(MethodDeclaration.class)) {
                method.getBody().ifPresent(body -> {
                    int lines = span(body);
                    if (lines > limit) {
                        issues.add(issue(ctx, method,
                                "方法 " + method.getNameAsString() + " 共 " + lines + " 行，超过阈值 " + limit,
                                "按职责拆分为多个小方法"));
                    }
                });
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 2. 圈复杂度过高
    // ------------------------------------------------------------------
    static final class ComplexityTooHighRule extends AbstractRule {

        ComplexityTooHighRule() {
            super("STYLE.COMPLEXITY_TOO_HIGH", "圈复杂度过高", IssueType.STYLE,
                    Severity.MAJOR, 1.0, "圈复杂度超过阈值，分支路径过多难以覆盖测试");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int limit = ctx.thresholds().getMethodMaxComplexity();
            List<Issue> issues = new ArrayList<>();
            // 直接复用 cq-parser 已算好的圈复杂度，不重复实现
            for (MethodInfo method : ctx.parsed().getSummary().getMethods()) {
                if (method.getCyclomaticComplexity() > limit) {
                    issues.add(issueAtLine(ctx, method.getStartLine(),
                            "方法 " + method.getName() + " 圈复杂度为 " + method.getCyclomaticComplexity()
                                    + "，超过阈值 " + limit,
                            "抽取条件分支为独立方法，或用卫语句提前返回"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 3. 类型过长
    // ------------------------------------------------------------------
    static final class ClassTooLongRule extends AbstractRule {

        ClassTooLongRule() {
            super("STYLE.CLASS_TOO_LONG", "类过长", IssueType.STYLE,
                    Severity.MINOR, 1.0, "类型行数超过阈值，通常意味着职责过多");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int limit = ctx.thresholds().getClassMaxLines();
            List<Issue> issues = new ArrayList<>();
            for (ClassInfo classInfo : ctx.parsed().getSummary().getClasses()) {
                // 匿名类的 range 取自 ObjectCreationExpr，与真实类型长度不是一回事
                if ("ANONYMOUS".equals(classInfo.getKind())) {
                    continue;
                }
                int lines = classInfo.getEndLine() - classInfo.getStartLine() + 1;
                if (lines > limit) {
                    issues.add(issueAtLine(ctx, classInfo.getStartLine(),
                            classInfo.getKind() + " " + classInfo.getName() + " 共 " + lines
                                    + " 行，超过阈值 " + limit,
                            "按职责拆分为多个类型"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 4. 参数过多
    // ------------------------------------------------------------------
    static final class TooManyParametersRule extends AbstractRule {

        TooManyParametersRule() {
            super("STYLE.TOO_MANY_PARAMS", "方法参数过多", IssueType.STYLE,
                    Severity.MINOR, 0.95, "参数个数超过阈值，调用易出错且难以维护");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int limit = ctx.thresholds().getMethodMaxParameters();
            List<Issue> issues = new ArrayList<>();
            for (MethodInfo method : ctx.parsed().getSummary().getMethods()) {
                int count = method.getParameters().size();
                if (count > limit) {
                    issues.add(issueAtLine(ctx, method.getStartLine(),
                            "方法 " + method.getName() + " 有 " + count + " 个参数，超过阈值 " + limit,
                            "把相关参数封装为参数对象"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 5. 类命名
    // ------------------------------------------------------------------
    static final class ClassNamingRule extends AbstractRule {

        ClassNamingRule() {
            super("STYLE.CLASS_NAMING", "类型命名不规范", IssueType.STYLE,
                    Severity.MINOR, 0.9, "类型名应使用大驼峰（UpperCamelCase）");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            String pattern = ctx.thresholds().getClassNamingPattern();
            List<Issue> issues = new ArrayList<>();
            for (ClassInfo classInfo : ctx.parsed().getSummary().getClasses()) {
                String name = classInfo.getName();
                // 匿名类名形如 Outer$1，本身不是人工命名
                if ("ANONYMOUS".equals(classInfo.getKind()) || name.contains("$")) {
                    continue;
                }
                if (!name.matches(pattern)) {
                    issues.add(issueAtLine(ctx, classInfo.getStartLine(),
                            "类型名 " + name + " 不符合大驼峰命名规范",
                            "改为 UpperCamelCase"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 6. 方法命名
    // ------------------------------------------------------------------
    static final class MethodNamingRule extends AbstractRule {

        MethodNamingRule() {
            super("STYLE.METHOD_NAMING", "方法命名不规范", IssueType.STYLE,
                    Severity.MINOR, 0.8, "方法名应使用小驼峰（lowerCamelCase）");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            String pattern = ctx.thresholds().getMethodNamingPattern();
            List<Issue> issues = new ArrayList<>();
            for (MethodInfo method : ctx.parsed().getSummary().getMethods()) {
                // 构造函数名必须等于类名，天然是大驼峰，不能报
                if (method.isConstructor()) {
                    continue;
                }
                if (!method.getName().matches(pattern)) {
                    issues.add(issueAtLine(ctx, method.getStartLine(),
                            "方法名 " + method.getName() + " 不符合小驼峰命名规范",
                            "改为 lowerCamelCase"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 7. 常量命名
    // ------------------------------------------------------------------
    static final class ConstantNamingRule extends AbstractRule {

        ConstantNamingRule() {
            super("STYLE.CONSTANT_NAMING", "常量命名不规范", IssueType.STYLE,
                    Severity.MINOR, 0.9, "static final 常量应使用 UPPER_SNAKE_CASE");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            String pattern = ctx.thresholds().getConstantNamingPattern();
            List<String> allowlist = ctx.thresholds().getConstantNameAllowlist();
            List<Issue> issues = new ArrayList<>();
            for (FieldDeclaration field : ctx.findAll(FieldDeclaration.class)) {
                if (!(field.isStatic() && field.isFinal())) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    // log / logger / serialVersionUID 等惯用名必须豁免，
                    // 否则每一条 private static final Logger log 都会被报出来
                    if (allowlist.contains(name) || name.matches(pattern)) {
                        continue;
                    }
                    issues.add(issue(ctx, variable,
                            "常量 " + name + " 不符合 UPPER_SNAKE_CASE 命名规范",
                            "改为全大写加下划线"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 8. 魔法数字
    // ------------------------------------------------------------------
    static final class MagicNumberRule extends AbstractRule {

        MagicNumberRule() {
            super("STYLE.MAGIC_NUMBER", "魔法数字", IssueType.STYLE,
                    Severity.MINOR, 0.5, "条件判断中出现含义不明的字面量数值，应提取为具名常量");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            List<String> allowlist = ctx.thresholds().getMagicNumberAllowlist();
            List<Issue> issues = new ArrayList<>();
            // 只查条件表达式中的比较：这是最没有歧义的场景，
            // 全面扫描数值字面量会把数组下标、注解参数等大量正常用法卷进来
            for (IfStmt statement : ctx.findAll(IfStmt.class)) {
                collect(ctx, statement.getCondition(), allowlist, issues);
            }
            for (WhileStmt statement : ctx.findAll(WhileStmt.class)) {
                collect(ctx, statement.getCondition(), allowlist, issues);
            }
            for (DoStmt statement : ctx.findAll(DoStmt.class)) {
                collect(ctx, statement.getCondition(), allowlist, issues);
            }
            return issues;
        }

        private void collect(RuleContext ctx, Expression condition, List<String> allowlist, List<Issue> issues) {
            for (BinaryExpr binary : condition.findAll(BinaryExpr.class)) {
                if (!isComparison(binary)) {
                    continue;
                }
                check(ctx, binary.getLeft(), binary.getRight(), allowlist, issues);
                check(ctx, binary.getRight(), binary.getLeft(), allowlist, issues);
            }
        }

        /** 一侧是数值字面量、另一侧是变量时才报告 */
        private void check(RuleContext ctx, Expression literalSide, Expression otherSide,
                           List<String> allowlist, List<Issue> issues) {
            String value = numericLiteral(literalSide);
            if (value == null || allowlist.contains(value.trim())) {
                return;
            }
            if (!(otherSide instanceof NameExpr)) {
                return;
            }
            issues.add(issue(ctx, literalSide,
                    "条件中使用了魔法数字 " + value,
                    "提取为具名常量，说明该数值的含义"));
        }

        private static boolean isComparison(BinaryExpr binary) {
            switch (binary.getOperator()) {
                case EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> {
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private static String numericLiteral(Expression expression) {
            if (expression instanceof IntegerLiteralExpr literal) {
                return literal.getValue();
            }
            if (expression instanceof LongLiteralExpr literal) {
                return literal.getValue();
            }
            if (expression instanceof DoubleLiteralExpr literal) {
                return literal.getValue();
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 9. 缺 Javadoc
    // ------------------------------------------------------------------
    static final class MissingJavadocRule extends AbstractRule {

        MissingJavadocRule() {
            super("STYLE.MISSING_JAVADOC", "public 方法缺 Javadoc", IssueType.STYLE,
                    Severity.MINOR, 0.6, "public 方法缺少 Javadoc 注释");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            if (!ctx.thresholds().isRequireJavadocForPublicMethods()) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            for (MethodDeclaration method : ctx.findAll(MethodDeclaration.class)) {
                if (!method.isPublic() || method.getJavadocComment().isPresent()) {
                    continue;
                }
                if (hasOverride(method) || isAccessor(method.getNameAsString())) {
                    continue;
                }
                issues.add(issue(ctx, method,
                        "public 方法 " + method.getNameAsString() + " 缺少 Javadoc",
                        "补充 Javadoc，说明用途、参数与返回值"));
            }
            return issues;
        }

        private static boolean hasOverride(MethodDeclaration method) {
            return method.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getNameAsString().endsWith("Override"));
        }

        /** getter/setter/isXxx 形式的方法豁免 */
        private static boolean isAccessor(String name) {
            if (name.startsWith("get") || name.startsWith("set")) {
                return name.length() > 3;
            }
            return name.startsWith("is") && name.length() > 2;
        }
    }

    // ------------------------------------------------------------------
    // 10. 嵌套过深
    // ------------------------------------------------------------------
    static final class DeepNestingRule extends AbstractRule {

        DeepNestingRule() {
            super("STYLE.DEEP_NESTING", "嵌套层数过深", IssueType.STYLE,
                    Severity.MAJOR, 0.85, "控制结构嵌套超过阈值，应使用卫语句或抽取方法");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int limit = ctx.thresholds().getMaxNestingDepth();
            List<Issue> issues = new ArrayList<>();
            for (MethodDeclaration method : ctx.findAll(MethodDeclaration.class)) {
                method.getBody().ifPresent(body -> {
                    int depth = maxDepth(body, 0);
                    if (depth > limit) {
                        issues.add(issue(ctx, method,
                                "方法 " + method.getNameAsString() + " 最大嵌套深度 " + depth
                                        + "，超过阈值 " + limit,
                                "用卫语句提前返回，或抽取内层逻辑为独立方法"));
                    }
                });
            }
            for (ConstructorDeclaration constructor : ctx.findAll(ConstructorDeclaration.class)) {
                int depth = maxDepth(constructor.getBody(), 0);
                if (depth > limit) {
                    issues.add(issue(ctx, constructor,
                            "构造函数最大嵌套深度 " + depth + "，超过阈值 " + limit,
                            "抽取内层逻辑为独立方法"));
                }
            }
            return issues;
        }

        /** 递归计算控制结构嵌套深度 */
        private static int maxDepth(Node node, int depth) {
            int max = depth;
            for (Node child : node.getChildNodes()) {
                if (AstScopeBoundary.isSeparateScope(child)) {
                    continue;   // 局部类/匿名类属于独立方法作用域
                }
                int next = depth;
                // else if 在 AST 中是嵌套 IfStmt，但它只是同一个判断链的延续，
                // 不能增加嵌套深度，否则每一级 else-if 都会命中
                if (isControlStructure(child) && !isElseIf(child)) {
                    next = depth + 1;
                }
                max = Math.max(max, maxDepth(child, next));
            }
            return max;
        }

        private static boolean isControlStructure(Node node) {
            return node instanceof IfStmt || node instanceof ForStmt || node instanceof ForEachStmt
                    || node instanceof WhileStmt || node instanceof DoStmt
                    || node instanceof SwitchStmt || node instanceof TryStmt;
        }

        private static boolean isElseIf(Node node) {
            if (!(node instanceof IfStmt)) {
                return false;
            }
            return node.getParentNode()
                    .filter(parent -> parent instanceof IfStmt)
                    .map(parent -> ((IfStmt) parent).getElseStmt().orElse(null) == node)
                    .orElse(false);
        }
    }

    // ------------------------------------------------------------------
    // 11. 未使用的 import
    // ------------------------------------------------------------------
    static final class UnusedImportRule extends AbstractRule {

        UnusedImportRule() {
            super("STYLE.UNUSED_IMPORT", "未使用的 import", IssueType.STYLE,
                    Severity.MINOR, 0.7, "import 语句未被使用，应清理");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            if (!ctx.parsed().hasUnit()) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            // 必须同时收集 SimpleName 与 Name：注解（如 @Mapper）在 JavaParser 中
            // 是 Name 而非 SimpleName，只查 SimpleName 会把每一个被注解使用的
            // import 都误判为未使用
            Set<String> referenced = new HashSet<>();
            collectReferencedNames(ctx, referenced);

            for (com.github.javaparser.ast.ImportDeclaration importDeclaration
                    : ctx.parsed().getUnit().getImports()) {
                if (importDeclaration.isAsterisk()) {
                    continue;   // 通配 import 无法静态判断是否使用
                }
                String qualified = importDeclaration.getNameAsString();
                int dot = qualified.lastIndexOf('.');
                String simpleName = dot >= 0 ? qualified.substring(dot + 1) : qualified;
                if (referenced.contains(simpleName)) {
                    continue;
                }
                // 注释里的 {@link Xxx} 引用在 AST 中不可见，用源码文本兜底
                if (referencedInJavadoc(ctx, simpleName)) {
                    continue;
                }
                issues.add(issueAtLine(ctx, importDeclaration.getBegin()
                                .map(position -> position.line).orElse(-1),
                        "import " + qualified + " 未被使用",
                        "删除该 import 语句"));
            }
            return issues;
        }

        /** 收集代码中出现的全部简单标识符 */
        private static void collectReferencedNames(RuleContext ctx, Set<String> target) {
            for (com.github.javaparser.ast.expr.SimpleName name
                    : ctx.findAll(com.github.javaparser.ast.expr.SimpleName.class)) {
                target.add(name.getIdentifier());
            }
            for (com.github.javaparser.ast.expr.Name name
                    : ctx.findAll(com.github.javaparser.ast.expr.Name.class)) {
                // import 语句自身的 Name 不算使用
                if (name.findAncestor(com.github.javaparser.ast.ImportDeclaration.class).isPresent()) {
                    continue;
                }
                target.add(name.getIdentifier());
            }
        }

        /** 是否在 Javadoc 的 {@code {@link Xxx}} 中被引用 */
        private static boolean referencedInJavadoc(RuleContext ctx, String simpleName) {
            String source = ctx.parsed().getSource();
            return source != null && (source.contains("{@link " + simpleName)
                    || source.contains("{@linkplain " + simpleName)
                    || source.contains("{@see " + simpleName));
        }
    }

    // ------------------------------------------------------------------
    // 12. 字段名过短
    // ------------------------------------------------------------------
    static final class ShortFieldNameRule extends AbstractRule {

        ShortFieldNameRule() {
            super("STYLE.SHORT_FIELD_NAME", "字段名过短", IssueType.STYLE,
                    Severity.MINOR, 0.6, "字段名过短难以理解其含义");
        }

        @Override
        public List<Issue> check(RuleContext ctx) {
            int minLength = ctx.thresholds().getMinFieldNameLength();
            List<String> allowlist = ctx.thresholds().getShortNameAllowlist();
            List<Issue> issues = new ArrayList<>();
            // 只查字段：若连同局部变量、循环计数器、lambda 参数、catch 参数一起查，
            // i/j/x/e 这类惯用短名会刷屏，规则会立刻被关掉
            for (FieldDeclaration field : ctx.findAll(FieldDeclaration.class)) {
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    if (name.length() >= minLength || allowlist.contains(name)) {
                        continue;
                    }
                    issues.add(issue(ctx, variable,
                            "字段名 " + name + " 过短，难以判断含义",
                            "改为有意义的完整名称"));
                }
            }
            return issues;
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 节点所在行跨度 */
    static int span(Node node) {
        return node.getRange()
                .map(range -> range.end.line - range.begin.line + 1)
                .orElse(0);
    }

    /** 作用域边界判断，避免为了一个方法把 AstScopeUtils 的依赖引到纯摘要规则里 */
    private static final class AstScopeBoundary {
        static boolean isSeparateScope(Node node) {
            return node instanceof TypeDeclaration;
        }
    }
}
