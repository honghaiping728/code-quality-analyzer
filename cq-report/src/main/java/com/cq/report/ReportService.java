package com.cq.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cq.common.Issue;
import com.cq.common.IssueStatus;
import com.cq.common.IssueType;
import com.cq.common.Severity;
import com.cq.common.model.IssueStat;
import com.cq.common.model.ScanReport;
import com.cq.common.model.ScanStats;
import com.cq.report.mapper.IssueStatMapper;
import com.cq.report.mapper.ScanReportMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 报告与趋势服务
 * <p>
 * 输入是一次扫描产出的事实（问题列表、文件数、总行数），输出是可持久化的汇总报告。
 * 刻意**不依赖扫描模块**：数据由调用方传入，使报告模块可以脱离扫描器独立测试。
 */
@Service
public class ReportService {

    private final ScanReportMapper reportMapper;
    private final IssueStatMapper issueStatMapper;

    public ReportService(ScanReportMapper reportMapper, IssueStatMapper issueStatMapper) {
        this.reportMapper = reportMapper;
        this.issueStatMapper = issueStatMapper;
    }

    /**
     * 生成并保存任务级报告
     * @param taskId 任务 ID
     * @param repoId 仓库 ID，可为 null
     * @param issues 本次扫描的全部问题
     * @param fileCount 扫描文件数
     * @param lineCount 代码总行数
     * @return 已保存的报告
     */
    public ScanReport generate(Long taskId, Long repoId, List<Issue> issues, int fileCount, long lineCount) {
        ScanReport report = new ScanReport();
        report.setTaskId(taskId);
        report.setRepoId(repoId);
        report.setTotalFiles(fileCount);
        report.setTotalLines(lineCount);
        report.setTotalIssues(issues.size());
        report.setBugCount(countType(issues, IssueType.BUG));
        report.setSecurityCount(countType(issues, IssueType.SECURITY));
        report.setPerformanceCount(countType(issues, IssueType.PERFORMANCE));
        report.setStyleCount(countType(issues, IssueType.STYLE));
        report.setBlockerCount(countSeverity(issues, Severity.BLOCKER));
        report.setCriticalCount(countSeverity(issues, Severity.CRITICAL));
        report.setMajorCount(countSeverity(issues, Severity.MAJOR));
        report.setMinorCount(countSeverity(issues, Severity.MINOR));
        // 问题密度按「每千行代码的问题数」计算，使不同规模的文件可横向比较
        report.setIssueDensity(lineCount > 0
                ? Math.round(issues.size() * 1000.0 / lineCount * 100.0) / 100.0
                : 0.0);
        long fixed = issues.stream().filter(i -> IssueStatus.FIXED.name().equals(i.getStatus())).count();
        long falsePositive = issues.stream()
                .filter(i -> IssueStatus.FALSE_POSITIVE.name().equals(i.getStatus())).count();
        report.setFixedCount((int) fixed);
        report.setFalsePositiveCount((int) falsePositive);
        report.setFixRate(issues.isEmpty() ? 0.0
                : Math.round(fixed * 10000.0 / issues.size()) / 10000.0);
        report.setCreateTime(new Date());

        // 同一任务重复生成报告时覆盖旧记录（task_id 上有唯一键）
        ScanReport existing = findByTask(taskId);
        if (existing != null) {
            report.setId(existing.getId());
            reportMapper.updateById(report);
        } else {
            reportMapper.insert(report);
        }
        saveStats(report, issues);
        return report;
    }

    /** 写入统计快照，供趋势图直接读取 */
    private void saveStats(ScanReport report, List<Issue> issues) {
        for (IssueType type : IssueType.values()) {
            insertStat(report, "TYPE", type.name(), countType(issues, type));
        }
        for (Severity severity : Severity.values()) {
            insertStat(report, "SEVERITY", severity.name(), countSeverity(issues, severity));
        }
    }

    private void insertStat(ScanReport report, String dimension, String value, int count) {
        try {
            LambdaQueryWrapper<IssueStat> query = new LambdaQueryWrapper<>();
            query.eq(IssueStat::getTaskId, report.getTaskId())
                    .eq(IssueStat::getDimension, dimension)
                    .eq(IssueStat::getDimValue, value);
            IssueStat existing = issueStatMapper.selectOne(query);
            if (existing != null) {
                existing.setIssueCount(count);
                issueStatMapper.updateById(existing);
                return;
            }
            IssueStat stat = new IssueStat();
            stat.setRepoId(report.getRepoId());
            stat.setTaskId(report.getTaskId());
            stat.setStatDate(new Date());
            stat.setDimension(dimension);
            stat.setDimValue(value);
            stat.setIssueCount(count);
            stat.setCreateTime(new Date());
            issueStatMapper.insert(stat);
        } catch (RuntimeException e) {
            // 统计快照是派生数据，写入失败不应影响主报告
            org.slf4j.LoggerFactory.getLogger(ReportService.class)
                    .debug("统计快照写入失败: {}", e.getMessage());
        }
    }

    /**
     * 按任务取报告
     * @param taskId 任务 ID
     * @return 报告，不存在时返回 null
     */
    public ScanReport findByTask(Long taskId) {
        if (taskId == null) {
            return null;
        }
        LambdaQueryWrapper<ScanReport> query = new LambdaQueryWrapper<>();
        query.eq(ScanReport::getTaskId, taskId).last("LIMIT 1");
        return reportMapper.selectOne(query);
    }

    /**
     * 质量趋势：按时间倒序取最近的报告
     * @param repoId 仓库 ID，为 null 时取全部
     * @param limit 条数上限
     * @return 报告列表，按时间倒序
     */
    public List<ScanReport> trend(Long repoId, int limit) {
        LambdaQueryWrapper<ScanReport> query = new LambdaQueryWrapper<>();
        if (repoId != null) {
            query.eq(ScanReport::getRepoId, repoId);
        }
        query.orderByDesc(ScanReport::getId).last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return reportMapper.selectList(query);
    }

    /**
     * 由问题列表直接汇总统计（不落库）
     * @param issues 问题列表
     * @param fileCount 文件数
     * @param lineCount 行数
     * @return 统计结果
     */
    public ScanStats stats(List<Issue> issues, int fileCount, long lineCount) {
        ScanStats stats = new ScanStats();
        for (Issue issue : issues) {
            stats.add(issue.getType(), issue.getSeverity(), issue.getRuleId());
        }
        stats.setFileCount(fileCount);
        stats.setLineCount(lineCount);
        stats.recalcDensity();
        return stats;
    }

    private static int countType(List<Issue> issues, IssueType type) {
        return (int) issues.stream().filter(i -> type.name().equals(i.getType())).count();
    }

    private static int countSeverity(List<Issue> issues, Severity severity) {
        return (int) issues.stream().filter(i -> severity.name().equals(i.getSeverity())).count();
    }
}
