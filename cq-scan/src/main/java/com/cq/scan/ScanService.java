package com.cq.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cq.common.Issue;
import com.cq.common.model.RuleConfigProvider;
import com.cq.common.model.RuleConfigSet;
import com.cq.common.model.ScanFile;
import com.cq.common.model.ScanTask;
import com.cq.parser.AstParserService;
import com.cq.parser.ParsedFile;
import com.cq.rule.RuleEngine;
import com.cq.scan.mapper.IssueMapper;
import com.cq.scan.mapper.ScanFileMapper;
import com.cq.scan.mapper.ScanTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 扫描编排服务
 * <p>
 * 串联整条链路：遍历目标目录 → AST 解析 → 规则引擎检查 → 落库。
 * 任务状态机为 PENDING → RUNNING → SUCCESS / FAILED，任一步骤抛异常都会把任务
 * 置为 FAILED 并记录原因，不会留下永远卡在 RUNNING 的僵尸任务。
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final ScanTaskMapper taskMapper;
    private final ScanFileMapper scanFileMapper;
    private final IssueMapper issueMapper;
    private final RuleConfigProvider configProvider;

    private final AstParserService parser = new AstParserService();
    private final RuleEngine engine = new RuleEngine();

    /** 异步执行扫描，避免大仓库阻塞请求线程 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cq-scan-worker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param taskMapper 任务 Mapper
     * @param scanFileMapper 文件明细 Mapper
     * @param issueMapper 问题 Mapper
     * @param configProvider 规则配置来源；按 ObjectProvider 注入，
     *                       未提供时退化为内置默认规则集
     */
    public ScanService(ScanTaskMapper taskMapper,
                       ScanFileMapper scanFileMapper,
                       IssueMapper issueMapper,
                       ObjectProvider<RuleConfigProvider> configProvider) {
        this.taskMapper = taskMapper;
        this.scanFileMapper = scanFileMapper;
        this.issueMapper = issueMapper;
        this.configProvider = configProvider.getIfAvailable();
    }

    // ==================== 对外接口 ====================

    /** 单次扫描最多接受的文件数，防止一次上传拖垮服务 */
    public static final int MAX_UPLOAD_FILES = 50;

    /** 单个源码单元的最大字符数 */
    public static final int MAX_SOURCE_CHARS = 2 * 1024 * 1024;

    /**
     * 提交扫描任务并异步执行
     * @param targetPath 目标目录
     * @param mode FULL 全量 | INCREMENTAL 增量
     * @param repoId 所属仓库，可为 null
     * @return 已创建的任务（状态为 PENDING 或 RUNNING），前端据此轮询进度
     */
    public ScanTask submit(String targetPath, String mode, Long repoId) {
        return submitInternal(targetPath, mode, repoId, null);
    }

    /**
     * 提交「上传文件 / 粘贴代码」类扫描
     * <p>
     * 与目录扫描共用同一条分析与落库链路，区别只在源码从哪来。上传与粘贴的内容
     * **只存在于内存中**：不落盘、不按文件名做任何文件系统操作，因此不存在
     * 路径穿越或磁盘写满的风险。
     * @param displayName 任务展示名，如文件名或「粘贴的代码」
     * @param mode UPLOAD | SNIPPET
     * @param units 源码单元列表
     * @return 已创建的任务
     */
    public ScanTask submitSources(String displayName, String mode, List<SourceUnit> units) {
        validateUnits(units);
        String safeName = (displayName == null || displayName.isBlank()) ? "未命名" : displayName.trim();
        return submitInternal(safeName, mode, null, units);
    }

    /**
     * 校验上传/粘贴的内容规模
     * <p>
     * 输入来自浏览器，必须有上限：单次请求携带的文件数、单个文件的体积都要卡住，
     * 否则一次大上传就可能把服务拖垮。抽成独立方法以便脱离 Spring 容器单测。
     * @param units 源码单元
     * @throws IllegalArgumentException 内容为空或超出限制
     */
    static void validateUnits(List<SourceUnit> units) {
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("没有可分析的内容");
        }
        if (units.size() > MAX_UPLOAD_FILES) {
            throw new IllegalArgumentException("一次最多分析 " + MAX_UPLOAD_FILES + " 个文件，当前 "
                    + units.size() + " 个");
        }
        for (SourceUnit unit : units) {
            if (unit.content() != null && unit.content().length() > MAX_SOURCE_CHARS) {
                throw new IllegalArgumentException(unit.displayPath() + " 内容过大（超过 "
                        + (MAX_SOURCE_CHARS / 1024 / 1024) + " MB）");
            }
        }
    }

    /**
     * 创建任务并异步执行
     * @param units 为 null 时按目录路径读取源码
     */
    private ScanTask submitInternal(String displayName, String mode, Long repoId, List<SourceUnit> units) {
        ScanTask task = new ScanTask();
        task.setTargetPath(displayName);
        task.setMode(mode == null ? "FULL" : mode);
        task.setRepoId(repoId);
        task.setTriggerType("MANUAL");
        task.setStatus("PENDING");
        task.setCreateTime(new Date());
        taskMapper.insert(task);

        executor.submit(() -> {
            try {
                if (units == null) {
                    execute(task);
                } else {
                    executeUnits(task, units);
                }
            } catch (RuntimeException e) {
                log.error("扫描任务 {} 执行失败", task.getId(), e);
            }
        });
        return task;
    }

    /**
     * 同步执行目录扫描（测试与内部调用用）
     * @param task 已入库的任务
     * @return 执行完毕的任务
     */
    public ScanTask execute(ScanTask task) {
        long startedAt = markRunning(task);
        try {
            RuleConfigSet config = currentConfig();
            List<SourceUnit> units = readSourceUnits(task.getTargetPath(), config);
            return analyzeUnits(task, units, config, startedAt);
        } catch (Exception e) {
            return markFailed(task, startedAt, e);
        }
    }

    /**
     * 同步执行源码单元扫描（上传 / 粘贴）
     * @param task 已入库的任务
     * @param units 源码单元
     * @return 执行完毕的任务
     */
    public ScanTask executeUnits(ScanTask task, List<SourceUnit> units) {
        long startedAt = markRunning(task);
        try {
            return analyzeUnits(task, units, currentConfig(), startedAt);
        } catch (Exception e) {
            return markFailed(task, startedAt, e);
        }
    }

    /** 逐单元解析、检查、落库 */
    private ScanTask analyzeUnits(ScanTask task, List<SourceUnit> units,
                                  RuleConfigSet config, long startedAt) {
        List<Issue> allIssues = new ArrayList<>();
        int totalLines = 0;
        int analyzed = 0;

        for (SourceUnit unit : units) {
            if (unit.isBlank()) {
                continue;   // 空文件或空白粘贴，没有分析价值
            }
            ParsedFile parsed = parser.parseDetailed(unit.content(), unit.displayPath());
            List<Issue> issues = engine.analyze(parsed, config);
            for (Issue issue : issues) {
                issue.setFilePath(unit.displayPath());
                issue.setTaskId(null);
            }
            allIssues.addAll(issues);
            int lineCount = countLines(parsed);
            totalLines += lineCount;
            analyzed++;

            ScanFile scanFile = new ScanFile();
            scanFile.setTaskId(task.getId());
            scanFile.setFilePath(unit.displayPath());
            scanFile.setLineCount(lineCount);
            scanFile.setIssueCount(issues.size());
            scanFile.setParsed(parsed.getSummary().isParsed());
            if (!parsed.getSummary().isParsed()) {
                scanFile.setParseError(String.join("; ", parsed.getSummary().getParseErrors()));
            }
            scanFileMapper.insert(scanFile);
        }

        persistIssues(task.getId(), allIssues);

        task.setFileCount(analyzed);
        task.setIssueCount(allIssues.size());
        task.setStatus("SUCCESS");
        long finishedAt = System.currentTimeMillis();
        task.setEndTime(new Date(finishedAt));
        task.setDurationMs(finishedAt - startedAt);
        taskMapper.updateById(task);
        log.info("扫描任务 {} 完成：{} 个文件，{} 行，{} 个问题，耗时 {}ms",
                task.getId(), analyzed, totalLines, allIssues.size(), task.getDurationMs());
        return task;
    }

    private RuleConfigSet currentConfig() {
        return configProvider == null ? new RuleConfigSet() : configProvider.current();
    }

    private long markRunning(ScanTask task) {
        long startedAt = System.currentTimeMillis();
        task.setStatus("RUNNING");
        task.setStartTime(new Date(startedAt));
        taskMapper.updateById(task);
        return startedAt;
    }

    private ScanTask markFailed(ScanTask task, long startedAt, Exception e) {
        log.error("扫描任务 {} 失败", task.getId(), e);
        task.setStatus("FAILED");
        task.setErrorMessage(e.getMessage());
        long finishedAt = System.currentTimeMillis();
        task.setEndTime(new Date(finishedAt));
        task.setDurationMs(finishedAt - startedAt);
        taskMapper.updateById(task);
        return task;
    }

    /**
     * 从目录读取源码单元
     * @param targetPath 目录或单个文件
     * @param config 规则配置，用于按忽略 glob 过滤
     * @return 源码单元列表
     */
    private List<SourceUnit> readSourceUnits(String targetPath, RuleConfigSet config) throws IOException {
        List<Path> javaFiles = collectJavaFiles(targetPath, config);
        List<SourceUnit> units = new ArrayList<>(javaFiles.size());
        for (Path path : javaFiles) {
            try {
                units.add(new SourceUnit(relativize(targetPath, path),
                        java.nio.file.Files.readString(path)));
            } catch (IOException e) {
                log.warn("文件读取失败，已跳过: {}", path, e);
            }
        }
        return units;
    }

    /**
     * 逐条落库问题
     * <p>
     * 单条插入而非批量：问题表有唯一键去重，重复扫描同一文件时批量插入会因
     * 主键/唯一键冲突整批失败，逐条插入可跳过重复项继续写入。
     * <p>
     * 写入失败必须**可见**：这里曾把所有异常都吞在 debug 级别，导致实体与表列不匹配时
     * 上百条问题静默丢失、而任务仍显示成功。现在区分「重复键（正常跳过）」与
     * 「其他异常（需要告警）」两类，后者累计后抛出，让任务如实标记为失败。
     */
    private void persistIssues(Long taskId, List<Issue> issues) {
        int duplicates = 0;
        int failures = 0;
        String lastError = null;
        for (Issue issue : issues) {
            issue.setTaskId(taskId);
            try {
                issueMapper.insert(issue);
            } catch (DuplicateKeyException e) {
                duplicates++;   // 同一任务内重复命中，属预期情况
            } catch (RuntimeException e) {
                failures++;
                if (lastError == null) {
                    lastError = e.getMessage();
                    log.warn("问题写入失败，样例：{} L{} — {}", issue.getRuleId(), issue.getLine(),
                            e.getMessage());
                }
            }
        }
        if (duplicates > 0) {
            log.debug("任务 {} 跳过 {} 条重复问题", taskId, duplicates);
        }
        if (failures > 0) {
            throw new IllegalStateException("有 " + failures + " 条问题未能写入数据库（共 "
                    + issues.size() + " 条）：" + lastError);
        }
    }

    // ==================== 查询 ====================

    /**
     * 按条件列出问题
     * @param taskId 任务 ID，可为 null
     * @param type 问题类型，可为 null
     * @param severity 严重级，可为 null
     * @param limit 返回上限
     * @return 问题列表
     */
    public List<Issue> listIssues(Long taskId, String type, String severity, int limit) {
        LambdaQueryWrapper<Issue> query = new LambdaQueryWrapper<>();
        if (taskId != null) {
            query.eq(Issue::getTaskId, taskId);
        }
        if (type != null && !type.isBlank()) {
            query.eq(Issue::getType, type);
        }
        if (severity != null && !severity.isBlank()) {
            query.eq(Issue::getSeverity, severity);
        }
        query.orderByAsc(Issue::getFilePath).orderByAsc(Issue::getLine);
        query.last("LIMIT " + Math.max(1, Math.min(limit, 5000)));
        return issueMapper.selectList(query);
    }

    /** 按任务列出问题 */
    public List<Issue> listIssues(Long taskId) {
        return listIssues(taskId, null, null, 5000);
    }

    /** 取单个问题 */
    public Issue getIssue(Long issueId) {
        return issueMapper.selectById(issueId);
    }

    /** 更新问题状态（确认 / 修复 / 误报） */
    public boolean updateIssueStatus(Long issueId, String status) {
        Issue issue = issueMapper.selectById(issueId);
        if (issue == null) {
            return false;
        }
        issue.setStatus(status);
        return issueMapper.updateById(issue) > 0;
    }

    /** 最近的任务列表 */
    public List<ScanTask> listTasks(int limit) {
        LambdaQueryWrapper<ScanTask> query = new LambdaQueryWrapper<>();
        query.orderByDesc(ScanTask::getId);
        query.last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return taskMapper.selectList(query);
    }

    /** 取单个任务 */
    public ScanTask getTask(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    /** 取某任务涉及的文件明细 */
    public List<ScanFile> listScanFiles(Long taskId) {
        LambdaQueryWrapper<ScanFile> query = new LambdaQueryWrapper<>();
        query.eq(ScanFile::getTaskId, taskId).orderByDesc(ScanFile::getIssueCount);
        return scanFileMapper.selectList(query);
    }

    /**
     * 某个任务扫描的代码总行数
     * <p>
     * 报告里的「问题密度」以千行代码为分母。缺少行数时密度会恒为 0，
     * 不同规模的项目之间也就无法横向比较。
     * @param taskId 任务 ID
     * @return 总行数
     */
    public long totalLines(Long taskId) {
        long total = 0;
        for (ScanFile file : listScanFiles(taskId)) {
            total += file.getLineCount();
        }
        return total;
    }

    // ==================== 内部 ====================

    /**
     * 收集目标目录下的 Java 文件
     * <p>
     * 按配置的忽略 glob 跳过 target、generated 等目录。
     */
    private List<Path> collectJavaFiles(String targetPath, RuleConfigSet config) throws IOException {
        Path root = Paths.get(targetPath);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("扫描路径不存在: " + targetPath);
        }
        if (Files.isRegularFile(root)) {
            return List.of(root);
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isIgnored(root, path, config))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isIgnored(Path root, Path file, RuleConfigSet config) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        for (String pattern : config.getThresholds().getIgnoredFilePatterns()) {
            if (relative.matches(com.cq.rule.RuleContext.globToRegex(pattern))) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(String root, Path file) {
        try {
            return Paths.get(root).relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    private static int countLines(ParsedFile parsed) {
        String source = parsed.getSource();
        if (source == null) {
            return 0;
        }
        return (int) source.lines().count();
    }

    /** 供测试与监控查看当前活跃线程数 */
    public int activeWorkerCount() {
        return ((java.util.concurrent.ThreadPoolExecutor) executor).getActiveCount();
    }
}
