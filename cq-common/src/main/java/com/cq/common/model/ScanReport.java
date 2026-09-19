package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 审查报告（任务级汇总）
 * <p>
 * 由 cq-report 在扫描结束后聚合生成，是质量报告页与趋势图的数据源。
 */
public class ScanReport implements Serializable {

    private Long id;
    private Long taskId;
    private Long repoId;
    private int totalFiles;             // 扫描文件数
    private long totalLines;            // 代码总行数
    private int totalIssues;            // 问题总数
    private int bugCount;               // 四维分布
    private int securityCount;
    private int performanceCount;
    private int styleCount;
    private int blockerCount;           // 严重级分布
    private int criticalCount;
    private int majorCount;
    private int minorCount;
    private double issueDensity;        // 问题密度：问题数 / 千行代码
    private int fixedCount;             // 已修复数
    private int falsePositiveCount;     // 误报数
    private double fixRate;             // 修复率 0-1
    private Date createTime;

    public ScanReport() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

    public long getTotalLines() { return totalLines; }
    public void setTotalLines(long totalLines) { this.totalLines = totalLines; }

    public int getTotalIssues() { return totalIssues; }
    public void setTotalIssues(int totalIssues) { this.totalIssues = totalIssues; }

    public int getBugCount() { return bugCount; }
    public void setBugCount(int bugCount) { this.bugCount = bugCount; }

    public int getSecurityCount() { return securityCount; }
    public void setSecurityCount(int securityCount) { this.securityCount = securityCount; }

    public int getPerformanceCount() { return performanceCount; }
    public void setPerformanceCount(int performanceCount) { this.performanceCount = performanceCount; }

    public int getStyleCount() { return styleCount; }
    public void setStyleCount(int styleCount) { this.styleCount = styleCount; }

    public int getBlockerCount() { return blockerCount; }
    public void setBlockerCount(int blockerCount) { this.blockerCount = blockerCount; }

    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int criticalCount) { this.criticalCount = criticalCount; }

    public int getMajorCount() { return majorCount; }
    public void setMajorCount(int majorCount) { this.majorCount = majorCount; }

    public int getMinorCount() { return minorCount; }
    public void setMinorCount(int minorCount) { this.minorCount = minorCount; }

    public double getIssueDensity() { return issueDensity; }
    public void setIssueDensity(double issueDensity) { this.issueDensity = issueDensity; }

    public int getFixedCount() { return fixedCount; }
    public void setFixedCount(int fixedCount) { this.fixedCount = fixedCount; }

    public int getFalsePositiveCount() { return falsePositiveCount; }
    public void setFalsePositiveCount(int falsePositiveCount) { this.falsePositiveCount = falsePositiveCount; }

    public double getFixRate() { return fixRate; }
    public void setFixRate(double fixRate) { this.fixRate = fixRate; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "ScanReport{task=" + taskId + ", files=" + totalFiles + ", issues=" + totalIssues
                + ", density=" + issueDensity + "}";
    }
}
