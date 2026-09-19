package com.cq.web;

import com.cq.common.Issue;
import com.cq.common.Result;
import com.cq.common.model.FixSuggestion;
import com.cq.scan.ScanService;
import com.cq.suggestion.SuggestionService;
import com.cq.web.dto.FeedbackRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 修复建议接口
 */
@RestController
@RequestMapping("/api/suggestions")
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final ScanService scanService;

    public SuggestionController(SuggestionService suggestionService, ScanService scanService) {
        this.suggestionService = suggestionService;
        this.scanService = scanService;
    }

    /**
     * 为指定问题生成修复建议（已存在则直接返回，避免重复产生模型费用）
     * @param issueId 问题 ID
     * @return 修复建议
     */
    @PostMapping("/{issueId}")
    public Result<FixSuggestion> generate(@PathVariable Long issueId) {
        Issue issue = scanService.getIssue(issueId);
        if (issue == null) {
            return Result.error("问题不存在: " + issueId);
        }
        return Result.success(suggestionService.generate(issue));
    }

    /**
     * 查询问题已有的修复建议
     * @param issueId 问题 ID
     * @return 修复建议，不存在时 data 为 null
     */
    @GetMapping("/{issueId}")
    public Result<FixSuggestion> get(@PathVariable Long issueId) {
        return Result.success(suggestionService.findByIssue(issueId));
    }

    /**
     * 提交采纳/拒绝/误报反馈
     * @param request 反馈内容
     * @return 操作结果
     */
    @PostMapping("/feedback")
    public Result<String> feedback(@RequestBody FeedbackRequest request) {
        if (request.getIssueId() == null || request.getAction() == null) {
            return Result.error("issueId 与 action 不能为空");
        }
        // 标记误报时同步更新问题状态，让反馈与实际处理结果保持一致
        if ("FALSE_POSITIVE".equalsIgnoreCase(request.getAction())) {
            scanService.updateIssueStatus(request.getIssueId(), "FALSE_POSITIVE");
        }
        suggestionService.recordFeedback(request.getIssueId(), request.getSuggestionId(),
                request.getAction().toUpperCase(), request.getRuleId(), request.getComment());
        return Result.success("反馈已记录");
    }

    /**
     * 查看某规则的采纳与误报统计（反馈自学习的数据视图）
     * @param ruleId 规则 ID
     * @return 统计结果
     */
    @GetMapping("/stats/{ruleId}")
    public Result<Map<String, Long>> stats(@PathVariable String ruleId) {
        long[] counts = suggestionService.feedbackStats(ruleId);
        Map<String, Long> result = new HashMap<>();
        result.put("accepted", counts[0]);
        result.put("falsePositive", counts[1]);
        return Result.success(result);
    }
}
