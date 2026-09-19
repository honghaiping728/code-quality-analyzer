package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 问题统计快照（趋势图数据源）
 * <p>
 * 按「仓库 × 任务 × 维度 × 维度取值」聚合，避免趋势查询时反复全表扫描 issue。
 */
public class IssueStat implements Serializable {

    private Long id;
    private Long repoId;        // 为 null 表示全局
    private Long taskId;
    private Date statDate;
    private String dimension;   // TYPE 按类型 | SEVERITY 按严重级
    private String dimValue;    // BUG / SECURITY / BLOCKER ...
    private int issueCount;
    private Date createTime;

    public IssueStat() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    public Date getStatDate() { return statDate; }
    public void setStatDate(Date statDate) { this.statDate = statDate; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public String getDimValue() { return dimValue; }
    public void setDimValue(String dimValue) { this.dimValue = dimValue; }

    public int getIssueCount() { return issueCount; }
    public void setIssueCount(int issueCount) { this.issueCount = issueCount; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "IssueStat{" + dimension + "=" + dimValue + ", count=" + issueCount + "}";
    }
}
