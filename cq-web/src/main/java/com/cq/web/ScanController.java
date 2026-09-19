package com.cq.web;

import com.cq.common.Result;
import com.cq.common.model.ScanFile;
import com.cq.common.model.ScanTask;
import com.cq.report.ReportService;
import com.cq.scan.ScanService;
import com.cq.web.dto.ScanRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 扫描任务接口
 */
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final ScanService scanService;
    private final ReportService reportService;

    public ScanController(ScanService scanService, ReportService reportService) {
        this.scanService = scanService;
        this.reportService = reportService;
    }

    /**
     * 提交扫描任务（异步执行，立即返回任务 ID 供前端轮询）
     * @param request 扫描请求
     * @return 已创建的任务
     */
    @PostMapping("/start")
    public Result<ScanTask> start(@RequestBody ScanRequest request) {
        if (request.getPath() == null || request.getPath().isBlank()) {
            return Result.error("扫描路径不能为空");
        }
        return Result.success(scanService.submit(request.getPath(), request.getMode(), request.getRepoId()));
    }

    /**
     * 任务列表
     * @param limit 返回上限
     * @return 任务列表
     */
    @GetMapping("/tasks")
    public Result<List<ScanTask>> tasks(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(scanService.listTasks(limit));
    }

    /**
     * 任务详情
     * @param id 任务 ID
     * @return 任务
     */
    @GetMapping("/tasks/{id}")
    public Result<ScanTask> task(@PathVariable Long id) {
        ScanTask task = scanService.getTask(id);
        if (task == null) {
            return Result.error("任务不存在: " + id);
        }
        return Result.success(task);
    }

    /**
     * 任务的文件明细
     * @param id 任务 ID
     * @return 文件列表
     */
    @GetMapping("/tasks/{id}/files")
    public Result<List<ScanFile>> files(@PathVariable Long id) {
        return Result.success(scanService.listScanFiles(id));
    }

    /**
     * 为已完成的任务生成报告
     * @param id 任务 ID
     * @return 生成结果说明
     */
    @PostMapping("/tasks/{id}/report")
    public Result<String> generateReport(@PathVariable Long id) {
        ScanTask task = scanService.getTask(id);
        if (task == null) {
            return Result.error("任务不存在: " + id);
        }
        var issues = scanService.listIssues(id);
        reportService.generate(id, task.getRepoId(), issues, task.getFileCount(),
                scanService.totalLines(id));
        return Result.success("报告已生成，共 " + issues.size() + " 个问题");
    }
}
