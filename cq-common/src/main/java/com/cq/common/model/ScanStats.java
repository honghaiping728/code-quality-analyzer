package com.cq.common.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问题统计结果
 * <p>
 * 用于报告页与规范统计接口，四个维度的计数按固定顺序（BUG/SECURITY/PERFORMANCE/STYLE）
 * 存放，便于前端直接渲染。
 */
public class ScanStats implements Serializable {

    private int totalIssues;
    private int fileCount;
    private long lineCount;
    private double issueDensity;    // 问题密度：问题数 / 千行代码
    private Map<String, Integer> byType = new LinkedHashMap<>();       // 按四大维度
    private Map<String, Integer> bySeverity = new LinkedHashMap<>();   // 按严重级
    private Map<String, Integer> byRule = new LinkedHashMap<>();       // 按规则，降序取 TopN 用

    public ScanStats() {}

    /** 累加一条问题计数 */
    public void add(String type, String severity, String ruleId) {
        totalIssues++;
        byType.merge(type, 1, Integer::sum);
        bySeverity.merge(severity, 1, Integer::sum);
        if (ruleId != null) {
            byRule.merge(ruleId, 1, Integer::sum);
        }
    }

    /** 依据已累计的计数重新计算问题密度 */
    public void recalcDensity() {
        issueDensity = lineCount > 0 ? Math.round(totalIssues * 1000.0 / lineCount * 100.0) / 100.0 : 0.0;
    }

    public int getTotalIssues() { return totalIssues; }
    public void setTotalIssues(int totalIssues) { this.totalIssues = totalIssues; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public long getLineCount() { return lineCount; }
    public void setLineCount(long lineCount) { this.lineCount = lineCount; }

    public double getIssueDensity() { return issueDensity; }
    public void setIssueDensity(double issueDensity) { this.issueDensity = issueDensity; }

    public Map<String, Integer> getByType() { return byType; }
    public void setByType(Map<String, Integer> byType) { this.byType = byType; }

    public Map<String, Integer> getBySeverity() { return bySeverity; }
    public void setBySeverity(Map<String, Integer> bySeverity) { this.bySeverity = bySeverity; }

    public Map<String, Integer> getByRule() { return byRule; }
    public void setByRule(Map<String, Integer> byRule) { this.byRule = byRule; }

    @Override
    public String toString() {
        return "ScanStats{issues=" + totalIssues + ", files=" + fileCount
                + ", lines=" + lineCount + ", density=" + issueDensity + "}";
    }
}
