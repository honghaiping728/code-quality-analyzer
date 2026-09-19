package com.cq.web;

import com.cq.common.Result;
import com.cq.common.model.ScanReport;
import com.cq.report.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报告与趋势接口
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 取某任务的报告
     * @param taskId 任务 ID
     * @return 报告，尚未生成时 data 为 null
     */
    @GetMapping("/{taskId}")
    public Result<ScanReport> byTask(@PathVariable Long taskId) {
        return Result.success(reportService.findByTask(taskId));
    }

    /**
     * 质量趋势：按时间倒序的报告序列
     * @param repoId 仓库 ID，缺省时取全部
     * @param limit 条数上限
     * @return 报告列表
     */
    @GetMapping("/trend")
    public Result<List<ScanReport>> trend(@RequestParam(required = false) Long repoId,
                                          @RequestParam(defaultValue = "30") int limit) {
        return Result.success(reportService.trend(repoId, limit));
    }
}
