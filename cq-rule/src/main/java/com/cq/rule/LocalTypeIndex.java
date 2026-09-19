package com.cq.rule;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 单文件局部类型索引
 * <p>
 * 项目只引入 {@code javaparser-core}、没有 SymbolSolver，因此类型不可知。这个索引用纯语法
 * 手段补足：记录「变量名 → 声明类型」，按方法作用域隔离以正确处理遮蔽（shadowing），
 * 找不到时回退到类字段。
 * <p>
 * 它直接决定了几条规则是否可用：
 * <ul>
 *     <li>{@code "... WHERE id=" + id} 中 {@code id} 是 {@code int} 时**不算** SQL 注入</li>
 *     <li>{@code List.contains} 与 {@code String.contains} 得以区分，后者不该报 O(n²)</li>
 *     <li>{@code BigDecimal.equals} 若不做类型判定，任何 {@code x.equals(y)} 都会命中</li>
 * </ul>
 * 这是在没有符号求解器的前提下，性价比最高的降噪手段。
 */
public final class LocalTypeIndex {

    /** 方法/构造函数 → 该作用域内的变量类型表 */
    private final Map<Node, Map<String, String>> byCallable = new IdentityHashMap<>();

    /** 类字段，作为方法内找不到时的回退 */
    private final Map<String, String> fields = new HashMap<>();

    private LocalTypeIndex() {}

    /**
     * 为一个编译单元建立索引
     * @param unit 编译单元
     * @return 类型索引
     */
    public static LocalTypeIndex build(CompilationUnit unit) {
        LocalTypeIndex index = new LocalTypeIndex();
        if (unit == null) {
            return index;
        }
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                index.fields.putIfAbsent(variable.getNameAsString(), variable.getTypeAsString());
            }
        }
        for (CallableDeclaration<?> callable : unit.findAll(CallableDeclaration.class)) {
            Map<String, String> scope = new HashMap<>();
            for (Parameter parameter : callable.getParameters()) {
                scope.put(parameter.getNameAsString(), parameter.getTypeAsString());
            }
            for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
                if (withinScope(variable, callable)) {
                    scope.put(variable.getNameAsString(), variable.getTypeAsString());
                }
            }
            for (Parameter parameter : callable.findAll(Parameter.class)) {
                if (withinScope(parameter, callable)) {
                    scope.put(parameter.getNameAsString(), parameter.getTypeAsString());
                }
            }
            index.byCallable.put(callable, scope);
        }
        return index;
    }

    /** 节点是否位于指定作用域内（途中不穿过嵌套类型声明） */
    private static boolean withinScope(Node node, Node scopeRoot) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != scopeRoot) {
            if (current instanceof TypeDeclaration) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return current == scopeRoot;
    }

    /**
     * 推断表达式的声明类型
     * @param expression 表达式
     * @return 剥去泛型后的简单类型名（如 {@code List}），无法推断时返回 null
     */
    public String typeOf(Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof StringLiteralExpr) {
            return "String";
        }
        if (expression instanceof IntegerLiteralExpr) {
            return "int";
        }
        if (expression instanceof LongLiteralExpr) {
            return "long";
        }
        if (expression instanceof DoubleLiteralExpr) {
            return "double";
        }
        if (expression instanceof CharLiteralExpr) {
            return "char";
        }
        if (expression instanceof BooleanLiteralExpr) {
            return "boolean";
        }
        if (expression instanceof ObjectCreationExpr creation) {
            return rawName(creation.getTypeAsString());
        }
        if (expression instanceof CastExpr cast) {
            return rawName(cast.getTypeAsString());
        }
        if (expression instanceof ThisExpr) {
            return null;
        }
        String name = nameOf(expression);
        return name == null ? null : lookup(expression, name);
    }

    /** 取表达式对应的变量名；无法归约为单个名字时返回 null */
    private static String nameOf(Expression expression) {
        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (expression instanceof FieldAccessExpr fieldAccess) {
            return fieldAccess.getNameAsString();
        }
        return null;
    }

    /** 按作用域逐级上溯查找变量类型 */
    private String lookup(Expression expression, String name) {
        Node current = expression.getParentNode().orElse(null);
        while (current != null) {
            Map<String, String> scope = byCallable.get(current);
            if (scope != null && scope.containsKey(name)) {
                return rawName(scope.get(name));
            }
            current = current.getParentNode().orElse(null);
        }
        String fieldType = fields.get(name);
        return fieldType == null ? null : rawName(fieldType);
    }

    /**
     * 剥去泛型与数组，取简单类型名
     * <p>
     * {@code java.util.List<String>[]} → {@code List}
     * @param typeText 类型文本
     * @return 简单类型名，入参为 null 时返回 null
     */
    public static String rawName(String typeText) {
        if (typeText == null) {
            return null;
        }
        int generic = typeText.indexOf('<');
        String base = generic >= 0 ? typeText.substring(0, generic) : typeText;
        base = base.replace("[]", "").trim();
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    /**
     * 表达式是否为指定类型
     * @param expression 表达式
     * @param typeNames 目标类型简单名，可多个
     * @return 是否匹配
     */
    public boolean isType(Expression expression, String... typeNames) {
        String type = typeOf(expression);
        if (type == null) {
            return false;
        }
        for (String candidate : typeNames) {
            if (candidate.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** 是否为字符串 */
    public boolean isString(Expression expression) {
        return isType(expression, "String", "StringBuilder", "StringBuffer", "CharSequence");
    }

    /** 是否为数值类型 */
    public boolean isNumeric(Expression expression) {
        return isType(expression, "int", "long", "short", "byte", "double", "float",
                "Integer", "Long", "Short", "Byte", "Double", "Float",
                "BigDecimal", "BigInteger", "Number");
    }

    /** 是否为整型（用于「除零」判定：{@code x / 0.0} 得到 Infinity，不应报告） */
    public boolean isIntegral(Expression expression) {
        return isType(expression, "int", "long", "short", "byte",
                "Integer", "Long", "Short", "Byte");
    }

    /** 是否为 List 实现（用于循环内 contains 的 O(n²) 判定） */
    public boolean isList(Expression expression) {
        return isType(expression, "List", "ArrayList", "LinkedList", "Vector", "Stack",
                "CopyOnWriteArrayList");
    }

    /** 是否为集合/映射（用于排除「循环内查库」对内存容器的误报） */
    public boolean isInMemoryCollection(Expression expression) {
        return isType(expression, "List", "ArrayList", "LinkedList", "Vector", "Stack",
                "Set", "HashSet", "TreeSet", "LinkedHashSet", "Map", "HashMap", "TreeMap",
                "LinkedHashMap", "ConcurrentHashMap", "Collection", "Deque", "ArrayDeque",
                "Queue", "Optional");
    }

    /** 是否为需要显式关闭的资源 */
    public boolean isResource(Expression expression) {
        return isType(expression, "InputStream", "OutputStream", "Reader", "Writer",
                "BufferedReader", "BufferedWriter", "FileInputStream", "FileOutputStream",
                "FileReader", "FileWriter", "Connection", "Statement", "PreparedStatement",
                "ResultSet", "Socket", "ServerSocket", "Channel", "FileChannel", "Scanner");
    }
}
