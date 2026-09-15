package com.cq.common.ast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 方法/构造函数的结构化信息
 * <p>
 * 字段可与 {@link com.cq.common.Issue} 直接对应，便于规则引擎产出问题对象：
 * {@code className/startLine} -> Issue.file/line，{@code cyclomaticComplexity} -> Issue.message 的判定依据。
 */
public class MethodInfo implements Serializable {

    private String name;                 // 方法名（构造函数为类名）
    private String className;            // 所属类简单名
    private String classQualifiedName;   // 所属类全限定名
    private String returnType;           // 返回类型，构造函数为 null
    private boolean constructor;         // 是否为构造函数
    private boolean staticMethod;        // 是否 static
    private List<String> modifiers = new ArrayList<>();       // 修饰符，如 public、final
    private List<ParamInfo> parameters = new ArrayList<>();   // 参数列表
    private List<String> thrownExceptions = new ArrayList<>();// throws 声明
    private int startLine;               // 起始行
    private int endLine;                 // 结束行
    private int cyclomaticComplexity;    // 圈复杂度，最小为 1
    private List<MethodCall> calls = new ArrayList<>();       // 直接调用点（调用图出边）

    public MethodInfo() {}

    /**
     * 方法唯一标识，调用图节点 ID
     * 形如 {@code com.cq.repo.GitService#getDiff(String,String,String)}
     */
    public String signature() {
        String owner = classQualifiedName != null ? classQualifiedName
                : (className != null ? className : "");
        StringBuilder sb = new StringBuilder(owner).append('#').append(name).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parameters.get(i).getType());
        }
        return sb.append(')').toString();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getClassQualifiedName() { return classQualifiedName; }
    public void setClassQualifiedName(String classQualifiedName) { this.classQualifiedName = classQualifiedName; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }

    public boolean isConstructor() { return constructor; }
    public void setConstructor(boolean constructor) { this.constructor = constructor; }

    public boolean isStaticMethod() { return staticMethod; }
    public void setStaticMethod(boolean staticMethod) { this.staticMethod = staticMethod; }

    public List<String> getModifiers() { return modifiers; }
    public void setModifiers(List<String> modifiers) { this.modifiers = modifiers; }

    public List<ParamInfo> getParameters() { return parameters; }
    public void setParameters(List<ParamInfo> parameters) { this.parameters = parameters; }

    public List<String> getThrownExceptions() { return thrownExceptions; }
    public void setThrownExceptions(List<String> thrownExceptions) { this.thrownExceptions = thrownExceptions; }

    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }

    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    public int getCyclomaticComplexity() { return cyclomaticComplexity; }
    public void setCyclomaticComplexity(int cyclomaticComplexity) { this.cyclomaticComplexity = cyclomaticComplexity; }

    public List<MethodCall> getCalls() { return calls; }
    public void setCalls(List<MethodCall> calls) { this.calls = calls; }

    @Override
    public String toString() {
        return signature() + " L" + startLine + "-" + endLine + " CC=" + cyclomaticComplexity;
    }
}
