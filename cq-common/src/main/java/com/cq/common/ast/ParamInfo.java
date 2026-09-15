package com.cq.common.ast;

import java.io.Serializable;

/**
 * 方法参数信息
 */
public class ParamInfo implements Serializable {

    private String type;      // 参数类型（泛型保留原始书写形式，如 List<String>）
    private String name;      // 参数名
    private boolean varArgs;  // 是否为可变参数（String... args）

    public ParamInfo() {}

    public ParamInfo(String type, String name, boolean varArgs) {
        this.type = type;
        this.name = name;
        this.varArgs = varArgs;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isVarArgs() { return varArgs; }
    public void setVarArgs(boolean varArgs) { this.varArgs = varArgs; }

    @Override
    public String toString() {
        return type + (varArgs ? "..." : "") + " " + name;
    }
}
