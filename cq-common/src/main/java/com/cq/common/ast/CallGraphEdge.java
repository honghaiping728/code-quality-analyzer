package com.cq.common.ast;

import java.io.Serializable;

/**
 * 调用图的一条边：caller -> callee
 */
public class CallGraphEdge implements Serializable {

    private String from;      // 调用方方法签名（本文件内声明的方法）
    private String to;        // 被调方：resolved=true 时为方法签名，否则为 scope.methodName 文本
    private int line;         // 调用所在行号
    private boolean resolved; // 是否解析到本文件内的方法

    public CallGraphEdge() {}

    public CallGraphEdge(String from, String to, int line, boolean resolved) {
        this.from = from;
        this.to = to;
        this.line = line;
        this.resolved = resolved;
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    @Override
    public String toString() {
        return from + " -> " + to + " (L" + line + (resolved ? "" : ", 未解析") + ")";
    }
}
