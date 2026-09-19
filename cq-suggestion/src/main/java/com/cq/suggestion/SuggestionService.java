package com.cq.suggestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cq.agent.AgentResult;
import com.cq.agent.CodeAgent;
import com.cq.common.Issue;
import com.cq.common.model.FixSuggestion;
import com.cq.common.model.SuggestionFeedback;
import com.cq.suggestion.mapper.FixSuggestionMapper;
import com.cq.suggestion.mapper.SuggestionFeedbackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 修复建议服务
 * <p>
 * 生成策略分两层：能套确定性模板的（如 {@code e.printStackTrace()} →
 * {@code log.error(...)}）直接产出高置信度修复；模板覆盖不到的语义级问题交给
 * Code Agent。低置信度建议在前端标注「供参考」，避免误导开发者。
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    /** 低于此置信度的建议在前端标注「供参考」 */
    public static final double REFERENCE_ONLY_THRESHOLD = 0.6;

    private final FixSuggestionMapper suggestionMapper;
    private final SuggestionFeedbackMapper feedbackMapper;
    private final CodeAgent codeAgent;

    public SuggestionService(FixSuggestionMapper suggestionMapper,
                             SuggestionFeedbackMapper feedbackMapper,
                             CodeAgent codeAgent) {
        this.suggestionMapper = suggestionMapper;
        this.feedbackMapper = feedbackMapper;
        this.codeAgent = codeAgent;
    }

    /**
     * 为指定问题生成修复建议
     * <p>
     * 已有建议时直接返回，避免重复调用大模型产生费用。
     * @param issue 问题对象
     * @return 修复建议
     */
    public FixSuggestion generate(Issue issue) {
        FixSuggestion existing = findByIssue(issue.getId());
        if (existing != null) {
            return existing;
        }

        FixSuggestion suggestion = new FixSuggestion();
        suggestion.setIssueId(issue.getId());
        suggestion.setOriginalCode(issue.getCodeSnippet());
        suggestion.setCreateTime(new Date());

        // 第一层：确定性模板
        FixTemplates.LineFix template = FixTemplates.templateFor(issue.getRuleId());
        String fixedLine = template == null ? null : template.apply(nullSafe(issue.getCodeSnippet()));
        if (fixedLine != null) {
            suggestion.setFixedCode(fixedLine);
            suggestion.setDiff(DiffBuilder.forSingleLine(
                    issue.getFilePath(), issue.getLine(), nullSafe(issue.getCodeSnippet()), fixedLine));
            suggestion.setExplanation("按规则 " + issue.getRuleId() + " 的标准写法替换为：" + fixedLine.trim());
            suggestion.setSource("TEMPLATE");
            suggestion.setConfidence(0.9);
        } else {
            // 第二层：交给 Code Agent 给出方向性建议（语法层面无法可靠生成完整补丁）
            AgentResult result = codeAgent.analyze(issue);
            suggestion.setExplanation(result.getCause());
            suggestion.setSource(result.isFromLlm() ? "LLM" : "TEMPLATE");
            suggestion.setConfidence(result.getConfidence());
            suggestion.setDiff(issue.getDiff());
            suggestion.setFixedCode(null);
        }

        suggestionMapper.insert(suggestion);
        return suggestion;
    }

    /** 查询某问题已有的建议 */
    public FixSuggestion findByIssue(Long issueId) {
        LambdaQueryWrapper<FixSuggestion> query = new LambdaQueryWrapper<>();
        query.eq(FixSuggestion::getIssueId, issueId).orderByDesc(FixSuggestion::getId).last("LIMIT 1");
        return suggestionMapper.selectOne(query);
    }

    /**
     * 记录采纳/拒绝/误报反馈
     * @param issueId 问题 ID
     * @param suggestionId 建议 ID，可为 null
     * @param action ACCEPT | REJECT | FALSE_POSITIVE
     * @param ruleId 规则 ID，冗余存储便于按规则统计
     * @param comment 备注
     * @return 反馈记录
     */
    public SuggestionFeedback recordFeedback(Long issueId, Long suggestionId,
                                             String action, String ruleId, String comment) {
        SuggestionFeedback feedback = new SuggestionFeedback();
        feedback.setIssueId(issueId);
        feedback.setSuggestionId(suggestionId);
        feedback.setRuleId(ruleId);
        feedback.setAction(action);
        feedback.setComment(comment);
        feedback.setCreateTime(new Date());
        feedbackMapper.insert(feedback);
        log.info("记录反馈：issue={} rule={} action={}", issueId, ruleId, action);
        return feedback;
    }

    /** 是否需要在界面上标注「供参考」 */
    public static boolean isReferenceOnly(FixSuggestion suggestion) {
        return suggestion.getConfidence() < REFERENCE_ONLY_THRESHOLD;
    }

    /** 某规则的反馈统计：[采纳数, 误报数] */
    public long[] feedbackStats(String ruleId) {
        LambdaQueryWrapper<SuggestionFeedback> query = new LambdaQueryWrapper<>();
        query.eq(SuggestionFeedback::getRuleId, ruleId);
        List<SuggestionFeedback> all = feedbackMapper.selectList(query);
        long accepted = all.stream().filter(f -> "ACCEPT".equals(f.getAction())).count();
        long falsePositive = all.stream().filter(f -> "FALSE_POSITIVE".equals(f.getAction())).count();
        return new long[]{accepted, falsePositive};
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
