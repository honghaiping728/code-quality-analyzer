package com.cq.agent;

import com.cq.agent.mapper.LlmCallLogMapper;
import com.cq.common.Issue;
import com.cq.common.model.LlmCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code Agent —— 双引擎中的语义分析引擎
 * <p>
 * 规则引擎负责「确定性地发现问题」，Code Agent 负责「解释成因并给出修复方向」。
 * 按 README 的「AST 辅助定位」思路，送入模型的不是整份文件，而是
 * **问题所在行 + 规则元信息** 构成的语义切片，既控制上下文长度又提升定位精度。
 * <p>
 * 每次调用都会写入 llm_call_log，记录 token 与耗时，使成本可观测。
 */
@Service
public class CodeAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一名资深 Java 代码审查专家。你会收到一条由静态规则检出、但尚未确认的问题。
            请判断它是否成立，并给出简洁、可执行的成因分析与修复方向。
            要求：
            1. 只针对给出的代码片段作答，不要假设未展示的代码。
            2. 若该问题在当前上下文中不成立（误报），请明确说明「疑似误报」并给出理由。
            3. 输出使用中文，控制在 200 字以内，不要重复粘贴原始代码。
            """;

    /** 从模型输出中识别「误报」判定，用于下调置信度 */
    private static final Pattern FALSE_POSITIVE_HINT = Pattern.compile("疑似误报|可能是误报|并非问题|不构成问题");

    /** 从模型输出中提取置信度调整建议，如「置信度：0.8」 */
    private static final Pattern CONFIDENCE_HINT = Pattern.compile("置信度[：:]\\s*(0?\\.\\d+|1\\.0|1)");

    private final LlmClient llmClient;
    private final LlmCallLogMapper llmCallLogMapper;

    public CodeAgent(LlmClient llmClient, LlmCallLogMapper llmCallLogMapper) {
        this.llmClient = llmClient;
        this.llmCallLogMapper = llmCallLogMapper;
    }

    /** 当前使用的客户端名称 */
    public String clientName() {
        return llmClient.name();
    }

    /** 大模型能力当前是否可用 */
    public boolean available() {
        return llmClient.available();
    }

    /**
     * 对一条规则命中做语义确认与成因分析
     * @param issue 规则检出但未确认的问题
     * @return 分析结果，包含成因、建议与调整后的置信度
     */
    public AgentResult analyze(Issue issue) {
        String prompt = buildPrompt(issue);
        LlmResponse response = llmClient.complete(SYSTEM_PROMPT, prompt);
        persistLog(issue, response);

        AgentResult result = new AgentResult();
        result.setModel(response.getModel());
        result.setFromLlm(!(llmClient instanceof OfflineHeuristicLlmClient));
        if (!response.isSuccess()) {
            // 调用失败时保留规则引擎的原始判定，不因模型不可用而丢结果
            log.warn("Code Agent 调用失败，沿用规则引擎判定: {}", response.getErrorMessage());
            result.setCause(issue.getCause());
            result.setSuggestion(issue.getSuggestion());
            result.setConfidence(issue.getConfidence());
            return result;
        }
        result.setCause(response.getContent());
        result.setSuggestion(issue.getSuggestion());
        result.setConfidence(adjustConfidence(issue.getConfidence(), response.getContent()));
        return result;
    }

    /**
     * 依据模型输出调整置信度
     * <p>
     * 模型显式判定为误报时大幅下调；本轮不直接删除问题，而是让下游按置信度阈值
     * 决定是否展示，避免模型偶发错误直接抹掉真实问题。
     */
    static double adjustConfidence(double original, String content) {
        if (content == null) {
            return original;
        }
        Matcher confidenceMatcher = CONFIDENCE_HINT.matcher(content);
        if (confidenceMatcher.find()) {
            try {
                double suggested = Double.parseDouble(confidenceMatcher.group(1));
                return Math.max(0.0, Math.min(1.0, suggested));
            } catch (NumberFormatException ignored) {
                // 落到下面的分支
            }
        }
        if (FALSE_POSITIVE_HINT.matcher(content).find()) {
            return Math.round(original * 0.3 * 100.0) / 100.0;
        }
        return original;
    }

    /**
     * 构造语义切片提示词
     * <p>
     * 只送问题所在行与规则元信息，不送整份文件 —— 这是控制 token 成本的关键。
     */
    String buildPrompt(Issue issue) {
        return """
                规则ID：%s
                问题类型：%s
                严重级：%s
                规则判定说明：%s
                文件：%s
                行号：%d
                问题描述：%s
                源代码行：
                %s
                """.formatted(
                nullSafe(issue.getRuleId()),
                nullSafe(issue.getType()),
                nullSafe(issue.getSeverity()),
                nullSafe(issue.getMessage()),
                nullSafe(issue.getFilePath()),
                issue.getLine(),
                nullSafe(issue.getMessage()),
                nullSafe(issue.getCodeSnippet()));
    }

    /** 记录调用日志；失败不影响主流程 */
    private void persistLog(Issue issue, LlmResponse response) {
        try {
            LlmCallLog callLog = new LlmCallLog();
            callLog.setTaskId(issue.getTaskId());
            callLog.setIssueId(issue.getId());
            callLog.setScene("CONFIRM");
            callLog.setModel(response.getModel());
            callLog.setPromptTokens(response.getPromptTokens());
            callLog.setCompletionTokens(response.getCompletionTokens());
            callLog.setTotalTokens(response.getTotalTokens());
            callLog.setDurationMs(response.getDurationMs());
            callLog.setSuccess(response.isSuccess());
            callLog.setErrorMessage(response.getErrorMessage());
            callLog.setCreateTime(new Date());
            llmCallLogMapper.insert(callLog);
        } catch (RuntimeException e) {
            log.debug("LLM 调用日志写入失败: {}", e.getMessage());
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
