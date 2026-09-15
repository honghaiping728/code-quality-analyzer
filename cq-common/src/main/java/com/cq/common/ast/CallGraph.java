package com.cq.common.ast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 单文件调用图
 * <p>
 * nodes 为本文件声明的方法签名（即调用图的顶点），edges 为方法间的调用关系。
 * 受限于未引入符号求解器，跨文件调用无法定位到具体重载，此时边的
 * {@code resolved=false}，{@code to} 为 {@code scope.methodName} 文本标识，
 * 该目标不会出现在 nodes 中。
 */
public class CallGraph implements Serializable {

    private List<String> nodes = new ArrayList<>();
    private List<CallGraphEdge> edges = new ArrayList<>();

    public CallGraph() {}

    public void addNode(String signature) {
        if (signature != null && !nodes.contains(signature)) {
            nodes.add(signature);
        }
    }

    public void addEdge(String from, String to, int line, boolean resolved) {
        edges.add(new CallGraphEdge(from, to, line, resolved));
    }

    public List<String> getNodes() { return nodes; }
    public void setNodes(List<String> nodes) { this.nodes = nodes; }

    public List<CallGraphEdge> getEdges() { return edges; }
    public void setEdges(List<CallGraphEdge> edges) { this.edges = edges; }

    @Override
    public String toString() {
        return "CallGraph{nodes=" + nodes.size() + ", edges=" + edges.size() + "}";
    }
}
