package com.cq.web;

import com.cq.common.Issue;
import com.cq.common.IssueStatus;
import com.cq.common.IssueType;
import com.cq.common.Result;
import com.cq.common.Severity;
import com.cq.common.model.ScanStats;
import com.cq.report.ReportService;
import com.cq.scan.ScanService;
import com.cq.web.dto.StatusRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 问题查询与处理接口
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final ScanService scanService;
    private final ReportService reportService;

    public IssueController(ScanService scanService, ReportService reportService) {
        this.scanService = scanService;
        this.reportService = reportService;
    }

    /**
     * 按条件查询问题
     * @param taskId 任务 ID
     * @param type 问题类型
     * @param severity 严重级
     * @param limit 上限
     * @return 问题列表
     */
    @GetMapping
    public Result<List<Issue>> list(@RequestParam(required = false) Long taskId,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String severity,
                                    @RequestParam(defaultValue = "500") int limit) {
        return Result.success(scanService.listIssues(taskId, type, severity, limit));
    }

    /**
     * 问题详情
     * @param id 问题 ID
     * @return 问题
     */
    @GetMapping("/{id}")
    public Result<Issue> detail(@PathVariable Long id) {
        Issue issue = scanService.getIssue(id);
        if (issue == null) {
            return Result.error("问题不存在: " + id);
        }
        return Result.success(issue);
    }

    /**
     * 更新问题状态（确认 / 已修复 / 误报）
     * @param id 问题 ID
     * @param request 状态请求
     * @return 操作结果
     */
    @PatchMapping("/{id}/status")
    public Result<Issue> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        IssueStatus status = parseStatus(request.getStatus());
        if (status == null) {
            return Result.error("非法状态: " + request.getStatus()
                    + "，可选值 " + Arrays.toString(IssueStatus.values()));
        }
        if (!scanService.updateIssueStatus(id, status.name())) {
            return Result.error("问题不存在: " + id);
        }
        return Result.success(scanService.getIssue(id));
    }

    /**
     * 问题统计（按维度与严重级聚合）
     * @param taskId 任务 ID，缺省时统计全部
     * @return 统计数据
     */
    @GetMapping("/stats")
    public Result<ScanStats> stats(@RequestParam(required = false) Long taskId) {
        List<Issue> issues = scanService.listIssues(taskId, null, null, 100000);
        return Result.success(reportService.stats(issues, 0, 0));
    }

    /** 枚举值与展示名的映射，供前端渲染筛选项 */
    @GetMapping("/dimensions")
    public Result<Map<String, List<String>>> dimensions() {
        return Result.success(Map.of(
                "types", Arrays.stream(IssueType.values()).map(Enum::name).collect(Collectors.toList()),
                "severities", Arrays.stream(Severity.values()).map(Enum::name).collect(Collectors.toList()),
                "statuses", Arrays.stream(IssueStatus.values()).map(Enum::name).collect(Collectors.toList())));
    }

    private static IssueStatus parseStatus(String value) {
        if (value == null) {
            return null;
        }
        for (IssueStatus status : IssueStatus.values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return null;
    }
}
