package com.cq.parser;

import com.cq.common.ast.AstSummary;
import com.cq.common.ast.CallGraph;
import com.cq.common.ast.ClassInfo;
import com.cq.common.ast.MethodCall;
import com.cq.common.ast.MethodInfo;
import com.cq.common.ast.ParamInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Problem;
import com.github.javaparser.Provider;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ReferenceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Java 源码 AST 解析服务（基于 JavaParser）
 * <p>
 * 输入 Java 源文件，输出结构化的 {@link AstSummary}，包含：
 * <ul>
 *     <li>类型结构：类/接口/枚举/记录/匿名类</li>
 *     <li>方法信息：方法名、参数、返回类型、throws、行号范围</li>
 *     <li>圈复杂度：见 {@link CyclomaticComplexityCalculator}</li>
 *     <li>调用图：方法之间的直接调用关系</li>
 * </ul>
 * <p>
 * 关于调用图的解析精度：本服务未引入 JavaParser 的 SymbolSolver（避免为解析引入完整的
 * 类路径依赖），因此只做文件内的启发式解析：
 * <ul>
 *     <li>无 scope 或 {@code this.} 的调用，若能唯一匹配到同类中的同名方法（必要时按实参个数区分重载），
 *         则边的 {@code resolved=true} 且目标为方法签名</li>
 *     <li>{@code ClassName.foo()} 中 ClassName 为本文件内声明的类型时，同样按上述规则解析</li>
 *     <li>其余情况（跨文件、变量接收者）无法定位到具体重载，{@code resolved=false}，
 *         目标退化为 {@code scope.methodName} 文本标识</li>
 * </ul>
 * <p>
 * 语法错误不会抛出异常，而是记录在 {@link AstSummary#getParseErrors()} 中，尽可能返回已解析出的部分结果。
 * 本类无可变状态，可安全地被多线程并发调用。
 */
public class AstParserService {

    private static final Logger log = LoggerFactory.getLogger(AstParserService.class);

    private static final String KIND_CLASS = "CLASS";
    private static final String KIND_INTERFACE = "INTERFACE";
    private static final String KIND_ENUM = "ENUM";
    private static final String KIND_RECORD = "RECORD";
    private static final String KIND_ANNOTATION = "ANNOTATION";
    private static final String KIND_ANONYMOUS = "ANONYMOUS";

    // ==================== 对外接口 ====================

    /**
     * 解析源码文本
     * @param sourceCode Java 源码
     * @return AST 摘要
     */
    public AstSummary parse(String sourceCode) {
        return buildSummary(parseSource(Providers.provider(new StringReader(sourceCode))), null);
    }

    /**
     * 解析单个 Java 文件
     * @param filePath 文件路径
     * @return AST 摘要
     */
    public AstSummary parseFile(String filePath) throws IOException {
        return parseFile(new File(filePath));
    }

    /**
     * 解析单个 Java 文件
     * @param file 文件对象
     * @return AST 摘要
     */
    public AstSummary parseFile(File file) throws IOException {
        return buildSummary(parseSource(Providers.provider(file)), file.getPath());
    }

    // ==================== 对外接口（携带原始 AST） ====================

    /**
     * 解析源码文本，同时返回结构化摘要与原始 AST
     * <p>
     * 与 {@link #parse(String)} 解析同一份代码，区别仅在于额外保留
     * {@link CompilationUnit} 与源码原文，供需要细粒度事实的规则使用。
     * 注意源码按 UTF-8 读取。
     * @param sourceCode Java 源码
     * @return 完整解析结果
     */
    public ParsedFile parseDetailed(String sourceCode) {
        return parseDetailed(sourceCode, null);
    }

    /**
     * 解析源码文本，并指定一个展示用路径
     * <p>
     * 用于「粘贴代码」「上传文件」这类没有真实磁盘路径的场景：给一份展示路径，
     * 规则中的忽略 glob 匹配、问题定位与报告展示才能正常工作。
     * @param sourceCode Java 源码
     * @param displayPath 展示用路径，可为 null
     * @return 完整解析结果
     */
    public ParsedFile parseDetailed(String sourceCode, String displayPath) {
        return buildParsedFile(sourceCode,
                parseSource(Providers.provider(new StringReader(sourceCode))), displayPath);
    }

    /**
     * 解析单个 Java 文件，同时返回结构化摘要与原始 AST
     * <p>
     * 源码以 UTF-8 读入后由此文本解析（而非交给 JavaParser 自行读取文件），
     * 保证「被解析的代码」与「规则见到的源码原文、行内容哈希」完全一致。
     * @param file 文件对象
     * @return 完整解析结果
     */
    public ParsedFile parseDetailed(File file) throws IOException {
        String source = Files.readString(file.toPath());
        return buildParsedFile(source, parseSource(Providers.provider(new StringReader(source))), file.getPath());
    }

    private ParsedFile buildParsedFile(String source, ParseResult<CompilationUnit> result, String filePath) {
        ParsedFile parsed = new ParsedFile();
        parsed.setSummary(buildSummary(result, filePath));
        parsed.setSource(source);
        parsed.setFilePath(filePath);
        parsed.setUnit(result.getResult().orElse(null));
        return parsed;
    }

    /**
     * 递归解析目录下的所有 .java 文件
     * <p>
     * 单个文件读取失败（IO 异常）只记录警告并跳过，不影响整体扫描；语法错误记录在各自的
     * {@link AstSummary#getParseErrors()} 中。
     * @param dirPath 目录路径
     * @return 每个文件对应一个 AST 摘要，按文件路径排序
     */
    public List<AstSummary> parseDirectory(String dirPath) throws IOException {
        Path root = Paths.get(dirPath);
        List<AstSummary> summaries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path path : javaFiles) {
                try {
                    summaries.add(parseFile(path.toFile()));
                } catch (IOException e) {
                    log.warn("文件解析失败，已跳过: {}", path, e);
                }
            }
        }
        return summaries;
    }

    // ==================== 解析 ====================

    private ParseResult<CompilationUnit> parseSource(Provider provider) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_21)
                // 必须保留注释：关闭后 Node.getComment()/getJavadocComment()/getAllComments()
                // 全部返回空（注释只在 CommentsInserter 中挂到节点上），会导致「缺 Javadoc」
                // 规则无法实现，且「空 catch」无法区分 catch {} 与 catch { /* 有意忽略 */ }
                .setAttributeComments(true);
        return new JavaParser(configuration).parse(ParseStart.COMPILATION_UNIT, provider);
    }

    private AstSummary buildSummary(ParseResult<CompilationUnit> result, String filePath) {
        AstSummary summary = new AstSummary();
        summary.setFilePath(filePath);
        for (Problem problem : result.getProblems()) {
            summary.getParseErrors().add(problem.getVerboseMessage());
        }
        if (result.getResult().isEmpty()) {
            log.warn("源码无法解析，返回空摘要: {}", filePath);
            return summary;
        }

        CompilationUnit cu = result.getResult().get();
        summary.setPackageName(cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse(""));
        for (ImportDeclaration importDeclaration : cu.getImports()) {
            summary.getImports().add((importDeclaration.isStatic() ? "static " : "")
                    + importDeclaration.getNameAsString());
        }

        Context ctx = buildClassIndex(cu);
        summary.getClasses().addAll(ctx.classes);
        collectMethods(cu, ctx, summary);
        summary.getMethods().sort(Comparator.comparingInt(MethodInfo::getStartLine));
        summary.getClasses().sort(Comparator.comparingInt(ClassInfo::getStartLine));
        buildCallGraph(ctx, summary);
        return summary;
    }

    // ==================== 类型提取 ====================

    /**
     * 建立「节点 -> 类型信息」索引，便于按 AST 父链定位某个方法与调用点所属类型。
     * 除声明的类型外，匿名类与枚举常量体也视为独立的类型（与方法作用域一致）。
     */
    private Context buildClassIndex(CompilationUnit cu) {
        Context ctx = new Context();

        List<TypeDeclaration<?>> declaredTypes = new ArrayList<>();
        declaredTypes.addAll(cu.findAll(ClassOrInterfaceDeclaration.class));
        declaredTypes.addAll(cu.findAll(EnumDeclaration.class));
        declaredTypes.addAll(cu.findAll(RecordDeclaration.class));
        declaredTypes.addAll(cu.findAll(AnnotationDeclaration.class));
        for (TypeDeclaration<?> type : declaredTypes) {
            ClassInfo info = toClassInfo(type);
            ctx.classIndex.put(type, info);
            ctx.classes.add(info);
        }

        // 匿名类：new Runnable() { ... }
        for (ObjectCreationExpr expr : cu.findAll(ObjectCreationExpr.class)) {
            if (expr.getAnonymousClassBody().isEmpty()) {
                continue;
            }
            ClassInfo info = anonymousClassInfo(expr, expr.getType().getNameAsString(), ctx);
            ctx.classIndex.put(expr, info);
            ctx.classes.add(info);
        }
        // 枚举常量体：enum Status { ACTIVE { ... } }
        for (EnumConstantDeclaration constant : cu.findAll(EnumConstantDeclaration.class)) {
            if (constant.getClassBody().isEmpty()) {
                continue;
            }
            String enumName = constant.findAncestor(EnumDeclaration.class)
                    .map(EnumDeclaration::getNameAsString)
                    .orElse("Enum");
            ClassInfo info = anonymousClassInfo(constant, enumName + "$" + constant.getNameAsString(), ctx);
            ctx.classIndex.put(constant, info);
            ctx.classes.add(info);
        }
        return ctx;
    }

    private ClassInfo toClassInfo(TypeDeclaration<?> type) {
        ClassInfo info = new ClassInfo();
        info.setName(type.getNameAsString());
        info.setQualifiedName(qualifiedName(type));
        info.setKind(kindOf(type));
        addModifiers(type, info.getModifiers());

        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            declaration.getExtendedTypes().forEach(parent -> info.getExtendedTypes().add(parent.asString()));
            declaration.getImplementedTypes().forEach(parent -> info.getExtendedTypes().add(parent.asString()));
        } else if (type instanceof EnumDeclaration declaration) {
            declaration.getImplementedTypes().forEach(parent -> info.getExtendedTypes().add(parent.asString()));
        } else if (type instanceof RecordDeclaration declaration) {
            declaration.getImplementedTypes().forEach(parent -> info.getExtendedTypes().add(parent.asString()));
        }
        applyRange(type, info);
        return info;
    }

    private ClassInfo anonymousClassInfo(Node node, String targetType, Context ctx) {
        ctx.anonymousSeq++;
        ClassInfo info = new ClassInfo();
        info.setName(nearestTypeName(node) + "$" + ctx.anonymousSeq);
        info.setQualifiedName(qualify(info.getName(), node));
        info.setKind(KIND_ANONYMOUS);
        info.getExtendedTypes().add(targetType);   // 匿名类的父类型
        applyRange(node, info);
        return info;
    }

    private static String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? KIND_INTERFACE : KIND_CLASS;
        }
        if (type instanceof EnumDeclaration) {
            return KIND_ENUM;
        }
        if (type instanceof RecordDeclaration) {
            return KIND_RECORD;
        }
        if (type instanceof AnnotationDeclaration) {
            return KIND_ANNOTATION;
        }
        return KIND_CLASS;
    }

    // ==================== 方法提取 ====================

    private void collectMethods(CompilationUnit cu, Context ctx, AstSummary summary) {
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            MethodInfo info = toMethodInfo(method, method.getType().asString(), false, ctx);
            // 接口/抽象方法没有方法体，圈复杂度保持 1
            method.getBody().ifPresent(body -> fillBody(info, body, method));
            summary.addMethod(info);
        }
        for (ConstructorDeclaration constructor : cu.findAll(ConstructorDeclaration.class)) {
            MethodInfo info = toMethodInfo(constructor, null, true, ctx);
            fillBody(info, constructor.getBody(), constructor);
            summary.addMethod(info);
        }
    }

    private MethodInfo toMethodInfo(CallableDeclaration<?> callable, String returnType,
                                    boolean isConstructor, Context ctx) {
        MethodInfo info = new MethodInfo();
        info.setName(callable.getNameAsString());
        ClassInfo owner = enclosingClass(callable, ctx);
        if (owner != null) {
            info.setClassName(owner.getName());
            info.setClassQualifiedName(owner.getQualifiedName());
        }
        info.setReturnType(returnType);
        info.setConstructor(isConstructor);
        addModifiers(callable, info.getModifiers());
        info.setStaticMethod(info.getModifiers().contains("static"));
        for (Parameter parameter : callable.getParameters()) {
            info.getParameters().add(new ParamInfo(
                    parameter.getType().asString(), parameter.getNameAsString(), parameter.isVarArgs()));
        }
        for (ReferenceType thrown : callable.getThrownExceptions()) {
            info.getThrownExceptions().add(thrown.asString());
        }
        applyRange(callable, info);
        info.setCyclomaticComplexity(1);
        ctx.methodsByClass.computeIfAbsent(info.getClassQualifiedName(), key -> new ArrayList<>()).add(info);
        return info;
    }

    /**
     * 基于方法体填充圈复杂度与调用点
     */
    private void fillBody(MethodInfo info, BlockStmt body, CallableDeclaration<?> callable) {
        info.setCyclomaticComplexity(CyclomaticComplexityCalculator.calculate(body));
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (belongsToCallable(call, callable)) {
                info.getCalls().add(toMethodCall(call));
            }
        }
    }

    private static MethodCall toMethodCall(MethodCallExpr expr) {
        MethodCall call = new MethodCall();
        call.setMethodName(expr.getNameAsString());
        call.setScope(expr.getScope().map(Expression::toString).orElse(null));
        call.setArgumentCount(expr.getArguments().size());
        call.setLine(expr.getBegin().map(position -> position.line).orElse(-1));
        call.setResolved(false);
        return call;
    }

    // ==================== 调用图 ====================

    private void buildCallGraph(Context ctx, AstSummary summary) {
        CallGraph graph = summary.getCallGraph();
        for (MethodInfo method : summary.getMethods()) {
            graph.addNode(method.signature());
        }
        for (MethodInfo method : summary.getMethods()) {
            for (MethodCall call : method.getCalls()) {
                resolveCall(ctx, call, method);
                graph.addEdge(method.signature(), call.getTarget(), call.getLine(), call.isResolved());
            }
        }
    }

    /**
     * 文件内启发式解析：优先在调用方所属类型内查找，其次查找本文件内声明的其他类型
     */
    private void resolveCall(Context ctx, MethodCall call, MethodInfo caller) {
        String scope = call.getScope();
        if (scope == null || scope.isEmpty() || "this".equals(scope)) {
            matchInClass(ctx, call, caller.getClassQualifiedName());
            return;
        }
        String candidateName = lastSegment(scope);
        for (ClassInfo classInfo : ctx.classes) {
            if (candidateName.equals(classInfo.getName()) || scope.equals(classInfo.getQualifiedName())) {
                matchInClass(ctx, call, classInfo.getQualifiedName());
                return;
            }
        }
        markUnresolved(call);
    }

    /**
     * 在指定类型内查找同名方法：无重载时直接命中，存在重载时按实参个数（含可变参数）区分
     */
    private void matchInClass(Context ctx, MethodCall call, String classQualifiedName) {
        List<MethodInfo> sameName = new ArrayList<>();
        for (MethodInfo method : ctx.methodsByClass.getOrDefault(classQualifiedName, List.of())) {
            if (method.getName().equals(call.getMethodName())) {
                sameName.add(method);
            }
        }
        if (sameName.size() == 1) {
            markResolved(call, sameName.get(0));
            return;
        }
        List<MethodInfo> arityMatch = new ArrayList<>();
        for (MethodInfo method : sameName) {
            boolean varArgs = method.getParameters().stream().anyMatch(ParamInfo::isVarArgs);
            if (varArgs || method.getParameters().size() == call.getArgumentCount()) {
                arityMatch.add(method);
            }
        }
        if (arityMatch.size() == 1) {
            markResolved(call, arityMatch.get(0));
            return;
        }
        markUnresolved(call);   // 无同名方法，或重载无法区分
    }

    private static void markResolved(MethodCall call, MethodInfo target) {
        call.setResolved(true);
        call.setTarget(target.signature());
    }

    private static void markUnresolved(MethodCall call) {
        call.setResolved(false);
        String scope = call.getScope();
        boolean local = scope == null || scope.isEmpty() || "this".equals(scope);
        call.setTarget(local ? call.getMethodName() : scope + "." + call.getMethodName());
    }

    // ==================== 工具方法 ====================

    /**
     * 判断调用点是否直接位于指定方法内（跳过局部类、匿名类、枚举常量体等嵌套作用域）
     */
    private static boolean belongsToCallable(Node node, CallableDeclaration<?> owner) {
        return AstScopeUtils.belongsToCallable(node, owner);
    }

    /**
     * 沿父链找到最近的类型（匿名类与枚举常量体也注册在索引中）
     */
    private static ClassInfo enclosingClass(Node node, Context ctx) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            ClassInfo info = ctx.classIndex.get(current);
            if (info != null) {
                return info;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private static String qualifiedName(TypeDeclaration<?> type) {
        List<String> names = new ArrayList<>();
        Node current = type;
        while (current != null) {
            if (current instanceof TypeDeclaration<?> declaration) {
                names.add(declaration.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        Collections.reverse(names);   // 由外到内
        return qualify(String.join(".", names), type);
    }

    private static String nearestTypeName(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> declaration) {
                return declaration.getNameAsString();
            }
            current = current.getParentNode().orElse(null);
        }
        return "Anonymous";
    }

    private static String qualify(String simpleName, Node node) {
        String packageName = node.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(PackageDeclaration::getNameAsString)
                .orElse("");
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static void addModifiers(NodeWithModifiers<?> node, List<String> target) {
        for (Modifier modifier : node.getModifiers()) {
            target.add(modifier.getKeyword().asString());
        }
    }

    private static void applyRange(Node node, ClassInfo info) {
        node.getRange().ifPresent(range -> {
            info.setStartLine(range.begin.line);
            info.setEndLine(range.end.line);
        });
    }

    private static void applyRange(Node node, MethodInfo info) {
        node.getRange().ifPresent(range -> {
            info.setStartLine(range.begin.line);
            info.setEndLine(range.end.line);
        });
    }

    private static String lastSegment(String text) {
        int index = text.lastIndexOf('.');
        return index < 0 ? text : text.substring(index + 1);
    }

    /**
     * 单次解析过程的上下文
     */
    private static final class Context {
        /** 节点 -> 类型信息（TypeDeclaration / 匿名类 ObjectCreationExpr / 枚举常量） */
        private final Map<Node, ClassInfo> classIndex = new IdentityHashMap<>();
        /** 文件内所有类型信息 */
        private final List<ClassInfo> classes = new ArrayList<>();
        /** 全限定类名 -> 该类声明的方法，用于调用解析 */
        private final Map<String, List<MethodInfo>> methodsByClass = new HashMap<>();
        /** 匿名类编号 */
        private int anonymousSeq;
    }
}
