package com.cq.common.ast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 类型（类/接口/枚举/记录/注解/匿名类）结构信息，不含方法体
 * <p>
 * 方法列表统一存放在 {@link AstSummary#getMethods()}，通过
 * {@link MethodInfo#getClassQualifiedName()} 与本对象关联。
 */
public class ClassInfo implements Serializable {

    private String name;              // 简单名
    private String qualifiedName;     // 全限定名
    private String kind;              // CLASS | INTERFACE | ENUM | RECORD | ANNOTATION | ANONYMOUS
    private List<String> modifiers = new ArrayList<>();      // 修饰符
    private List<String> extendedTypes = new ArrayList<>();  // extends / implements 的类型
    private int startLine;
    private int endLine;

    public ClassInfo() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public List<String> getModifiers() { return modifiers; }
    public void setModifiers(List<String> modifiers) { this.modifiers = modifiers; }

    public List<String> getExtendedTypes() { return extendedTypes; }
    public void setExtendedTypes(List<String> extendedTypes) { this.extendedTypes = extendedTypes; }

    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }

    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    @Override
    public String toString() {
        return kind + " " + qualifiedName + " L" + startLine + "-" + endLine;
    }
}
