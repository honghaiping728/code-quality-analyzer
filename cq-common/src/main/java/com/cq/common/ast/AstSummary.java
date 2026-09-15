package com.cq.common.ast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Java 文件的结构化 AST 摘要
 * <p>
 * 与 {@link com.cq.common.Issue} 采用相同的设计风格：纯 POJO + 默认值初始化，
 * 供 parser / rule / agent / report 模块之间通过 JSON 流转。
 * <p>
 * 结构说明：
 * <ul>
 *     <li>{@link #getClasses()} 描述文件内的类型结构（类/接口/枚举/记录/匿名类），不含方法体</li>
 *     <li>{@link #getMethods()} 为文件中所有方法与构造函数的扁平列表，
 *         通过 {@link MethodInfo#getClassQualifiedName()} 与 classes 关联</li>
 *     <li>{@link #getCallGraph()} 为文件内的调用关系</li>
 *     <li>{@link #getMethodCount()} / {@link #getMaxComplexity()} / {@link #getAvgComplexity()}
 *         为派生统计字段，通过 {@link #addMethod(MethodInfo)} 自动维护</li>
 * </ul>
 */
public class AstSummary implements Serializable {

    private String filePath;                                        // 文件路径
    private String packageName;                                     // 包名，默认包为空串
    private List<String> imports = new ArrayList<>();               // import 语句（不含 import static 的 static 标记）
    private List<ClassInfo> classes = new ArrayList<>();            // 类型列表
    private List<MethodInfo> methods = new ArrayList<>();           // 方法（含构造函数）扁平列表
    private CallGraph callGraph = new CallGraph();                  // 调用图
    private int methodCount;                                        // 派生：方法总数
    private int maxComplexity;                                      // 派生：单方法最大圈复杂度
    private double avgComplexity;                                   // 派生：平均圈复杂度（保留两位小数）
    private List<String> parseErrors = new ArrayList<>();           // 解析问题（语法错误等），可为空

    public AstSummary() {}

    /**
     * 添加一个方法，并同步更新派生统计字段
     */
    public void addMethod(MethodInfo method) {
        methods.add(method);
        methodCount = methods.size();
        if (method.getCyclomaticComplexity() > maxComplexity) {
            maxComplexity = method.getCyclomaticComplexity();
        }
        int total = 0;
        for (MethodInfo m : methods) {
            total += m.getCyclomaticComplexity();
        }
        avgComplexity = Math.round(total * 100.0 / methodCount) / 100.0;
    }

    /** 解析是否完全成功（无语法错误） */
    public boolean isParsed() {
        return parseErrors.isEmpty();
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public List<String> getImports() { return imports; }
    public void setImports(List<String> imports) { this.imports = imports; }

    public List<ClassInfo> getClasses() { return classes; }
    public void setClasses(List<ClassInfo> classes) { this.classes = classes; }

    public List<MethodInfo> getMethods() { return methods; }
    public void setMethods(List<MethodInfo> methods) { this.methods = methods; }

    public CallGraph getCallGraph() { return callGraph; }
    public void setCallGraph(CallGraph callGraph) { this.callGraph = callGraph; }

    public int getMethodCount() { return methodCount; }
    public void setMethodCount(int methodCount) { this.methodCount = methodCount; }

    public int getMaxComplexity() { return maxComplexity; }
    public void setMaxComplexity(int maxComplexity) { this.maxComplexity = maxComplexity; }

    public double getAvgComplexity() { return avgComplexity; }
    public void setAvgComplexity(double avgComplexity) { this.avgComplexity = avgComplexity; }

    public List<String> getParseErrors() { return parseErrors; }
    public void setParseErrors(List<String> parseErrors) { this.parseErrors = parseErrors; }

    @Override
    public String toString() {
        return "AstSummary{file='" + filePath + "', classes=" + classes.size()
                + ", methods=" + methodCount + ", edges=" + callGraph.getEdges().size()
                + ", maxCC=" + maxComplexity + "}";
    }
}
