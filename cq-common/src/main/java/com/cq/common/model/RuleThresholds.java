package com.cq.common.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则阈值与白名单
 * <p>
 * 采用「POJO + 默认值初始化」风格（同 {@link com.cq.common.Issue}），因此
 * {@code new RuleThresholds()} 就是一个开箱即用的合法配置，不存在「未配置」状态。
 * <p>
 * 白名单不是可有可无的装饰：{@code constantNameAllowlist} 不包含 {@code log} 时，
 * 常量命名规则会命中每一个 {@code private static final Logger log}，规则可信度会立刻崩塌。
 */
public class RuleThresholds implements Serializable {

    private int methodMaxLines = 50;                // 方法最大行数
    private int methodMaxComplexity = 10;           // 方法最大圈复杂度
    private int classMaxLines = 500;                // 类型最大行数
    private int methodMaxParameters = 5;            // 方法最大参数个数
    private int maxNestingDepth = 3;                // 最大嵌套深度（else if 不计入）
    private int minFieldNameLength = 2;             // 字段最小名字长度
    private int maxFindingsPerRulePerFile = 100;    // 单文件单规则命中数上限
    private boolean requireJavadocForPublicMethods = true;

    private String classNamingPattern = "^[A-Z][A-Za-z0-9]*$";
    private String methodNamingPattern = "^[a-z][A-Za-z0-9]*$";
    private String constantNamingPattern = "^[A-Z][A-Z0-9_]*$";

    private List<String> constantNameAllowlist = new ArrayList<>(List.of("log", "logger", "serialVersionUID"));
    private List<String> shortNameAllowlist = new ArrayList<>(List.of("i", "j", "k", "x", "y", "z", "e", "t", "id"));
    private List<String> magicNumberAllowlist = new ArrayList<>(List.of("0", "1", "2", "-1", "10", "100", "1000"));
    private List<String> ignoredFilePatterns = new ArrayList<>(List.of("**/target/**", "**/generated/**"));

    public RuleThresholds() {}

    public int getMethodMaxLines() { return methodMaxLines; }
    public void setMethodMaxLines(int methodMaxLines) { this.methodMaxLines = methodMaxLines; }

    public int getMethodMaxComplexity() { return methodMaxComplexity; }
    public void setMethodMaxComplexity(int methodMaxComplexity) { this.methodMaxComplexity = methodMaxComplexity; }

    public int getClassMaxLines() { return classMaxLines; }
    public void setClassMaxLines(int classMaxLines) { this.classMaxLines = classMaxLines; }

    public int getMethodMaxParameters() { return methodMaxParameters; }
    public void setMethodMaxParameters(int methodMaxParameters) { this.methodMaxParameters = methodMaxParameters; }

    public int getMaxNestingDepth() { return maxNestingDepth; }
    public void setMaxNestingDepth(int maxNestingDepth) { this.maxNestingDepth = maxNestingDepth; }

    public int getMinFieldNameLength() { return minFieldNameLength; }
    public void setMinFieldNameLength(int minFieldNameLength) { this.minFieldNameLength = minFieldNameLength; }

    public int getMaxFindingsPerRulePerFile() { return maxFindingsPerRulePerFile; }
    public void setMaxFindingsPerRulePerFile(int maxFindingsPerRulePerFile) { this.maxFindingsPerRulePerFile = maxFindingsPerRulePerFile; }

    public boolean isRequireJavadocForPublicMethods() { return requireJavadocForPublicMethods; }
    public void setRequireJavadocForPublicMethods(boolean requireJavadocForPublicMethods) { this.requireJavadocForPublicMethods = requireJavadocForPublicMethods; }

    public String getClassNamingPattern() { return classNamingPattern; }
    public void setClassNamingPattern(String classNamingPattern) { this.classNamingPattern = classNamingPattern; }

    public String getMethodNamingPattern() { return methodNamingPattern; }
    public void setMethodNamingPattern(String methodNamingPattern) { this.methodNamingPattern = methodNamingPattern; }

    public String getConstantNamingPattern() { return constantNamingPattern; }
    public void setConstantNamingPattern(String constantNamingPattern) { this.constantNamingPattern = constantNamingPattern; }

    public List<String> getConstantNameAllowlist() { return constantNameAllowlist; }
    public void setConstantNameAllowlist(List<String> constantNameAllowlist) { this.constantNameAllowlist = constantNameAllowlist; }

    public List<String> getShortNameAllowlist() { return shortNameAllowlist; }
    public void setShortNameAllowlist(List<String> shortNameAllowlist) { this.shortNameAllowlist = shortNameAllowlist; }

    public List<String> getMagicNumberAllowlist() { return magicNumberAllowlist; }
    public void setMagicNumberAllowlist(List<String> magicNumberAllowlist) { this.magicNumberAllowlist = magicNumberAllowlist; }

    public List<String> getIgnoredFilePatterns() { return ignoredFilePatterns; }
    public void setIgnoredFilePatterns(List<String> ignoredFilePatterns) { this.ignoredFilePatterns = ignoredFilePatterns; }
}
