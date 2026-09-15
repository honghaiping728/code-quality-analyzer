package com.cq.common.ast;

import java.io.Serializable;

/**
 * 方法调用点（调用图中的一条出边）
 * <p>
 * 未引入符号求解器（SymbolSolver），因此只能做文件内的启发式解析：
 * 无 scope（{@code foo()}）或 {@code this.foo()} 的调用，若能唯一匹配到本文件内
 * 同名（且参数个数一致）的方法，则 {@code resolved=true} 且 {@code target} 为方法签名；
 * 否则 {@code target} 退化为 {@code scope.methodName} 这样的文本标识。
 */
public class MethodCall implements Serializable {

    private String methodName;   // 被调用方法名，如 getDiff
    private String scope;        // 调用者表达式文本，如 git、System.out、this；无 scope 时为 null
    private int argumentCount;   // 实参个数
    private int line;            // 调用所在行号
    private boolean resolved;    // 是否解析到本文件内的方法
    private String target;       // 解析目标：resolved=true 时为方法签名，否则为 scope.methodName

    public MethodCall() {}

    /** 调用点的文本形式，如 git.getDiff */
    public String display() {
        return scope == null ? methodName : scope + "." + methodName;
    }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public int getArgumentCount() { return argumentCount; }
    public void setArgumentCount(int argumentCount) { this.argumentCount = argumentCount; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    @Override
    public String toString() {
        return display() + ":" + line;
    }
}
