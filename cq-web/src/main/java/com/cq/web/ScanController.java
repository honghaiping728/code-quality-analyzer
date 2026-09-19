package com.cq.web;

import com.cq.common.Result;
import com.cq.common.model.ScanFile;
import com.cq.common.model.ScanTask;
import com.cq.report.ReportService;
import com.cq.scan.ScanService;
import com.cq.scan.SourceUnit;
import com.cq.web.dto.ScanRequest;
import com.cq.web.dto.SnippetRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * 上传本地文件审查
     * <p>
     * 文件内容只在内存中解析，不落盘。只接受 .java 文件 —— 本分析器目前仅支持 Java，
     * 放行其他类型只会得到一堆解析错误，反而让人误以为分析失败。
     * @param files 上传的文件
     * @return 已创建的任务
     */
    @PostMapping("/upload")
    public Result<ScanTask> upload(
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return Result.error("请至少选择一个文件");
        }
        List<SourceUnit> units = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = displayNameOf(file);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".java")) {
                rejected.add(name);
                continue;
            }
            String content;
            try {
                content = new String(file.getBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return Result.error("读取文件失败: " + name + " — " + e.getMessage());
            }
            units.add(new SourceUnit(name, content));
        }
        if (units.isEmpty()) {
            return Result.error("只支持 .java 文件，以下文件已跳过: " + String.join("、", rejected));
        }
        String display = units.size() == 1
                ? units.get(0).displayPath()
                : units.size() + " 个上传文件";
        StringBuilder note = new StringBuilder();
        if (!rejected.isEmpty()) {
            note.append("已跳过非 Java 文件: ").append(String.join("、", rejected));
        }
        return Result.success(scanService.submitSources(display, "UPLOAD", units), note.toString());
    }

    /**
     * 粘贴代码审查
     * @param request 含 source 的请求体
     * @return 已创建的任务
     */
    @PostMapping("/snippet")
    public Result<ScanTask> snippet(@RequestBody SnippetRequest request) {
        if (request.getSource() == null || request.getSource().isBlank()) {
            return Result.error("请粘贴要审查的代码");
        }
        String fileName = (request.getFileName() == null || request.getFileName().isBlank())
                ? "粘贴的代码.java"
                : request.getFileName().trim();
        try {
            return Result.success(scanService.submitSources(
                    fileName, "SNIPPET", List.of(new SourceUnit(fileName, request.getSource()))));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 上传文件的展示名
     * <p>
     * 只取原始文件名的最后一段并剥离路径分隔符：浏览器传来的文件名由客户端控制，
     * 直接使用可能包含 `../` 一类内容。这里不落盘、不做文件系统操作，但仍要保证
     * 写进数据库与页面展示的名字是干净的。
     * @param file 上传文件
     * @return 安全的展示名
     */
    private static String displayNameOf(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "未命名.java";
        }
        String normalized = original.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.isBlank() ? "未命名.java" : name;
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
