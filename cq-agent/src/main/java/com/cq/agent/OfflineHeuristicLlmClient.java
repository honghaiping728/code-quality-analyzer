package com.cq.agent;

import java.util.Locale;

/**
 * 离线兜底客户端
 * <p>
 * 未配置 API Key 时启用，依据提示词中的规则 ID 给出**确定性**的成因模板与修复建议。
 * <p>
 * 它不是"假装调用大模型"：产出内容由规则元数据推导而来，可重复、可测试，
 * 目的是让「扫描 → 建议生成 → 报告」整条链路在任何环境下都能跑通并验收，
 * 而不是让功能在缺少密钥时直接断掉。真实语义分析仍需配置 API Key 后切换到
 * {@link OpenAiCompatibleLlmClient}。
 */
public class OfflineHeuristicLlmClient implements LlmClient {

    @Override
    public String name() {
        return "离线启发式（未配置 API Key）";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        long startedAt = System.currentTimeMillis();
        String ruleId = extract(userPrompt, "规则ID：");
        String message = extract(userPrompt, "问题描述：");
        String content = """
                成因分析：%s
                该问题由静态规则 %s 检出。%s

                修复方向：%s

                （说明：当前为离线启发式结果，配置 cq.llm.api-key 后将由大模型给出
                结合上下文的成因解释与修复代码。）
                """.formatted(
                message.isEmpty() ? "该处代码违反了既定规则。" : message,
                ruleId.isEmpty() ? "未知规则" : ruleId,
                causeHint(ruleId),
                fixHint(ruleId));
        LlmResponse response = LlmResponse.of(content.trim(), name(), System.currentTimeMillis() - startedAt);
        response.setCompletionTokens(content.length() / 4);   // 粗估，仅用于成本量级展示
        response.setTotalTokens(response.getCompletionTokens());
        return response;
    }

    /** 从提示词中取某一行的值 */
    private static String extract(String prompt, String label) {
        if (prompt == null) {
            return "";
        }
        int index = prompt.indexOf(label);
        if (index < 0) {
            return "";
        }
        int start = index + label.length();
        int end = prompt.indexOf('\n', start);
        return (end < 0 ? prompt.substring(start) : prompt.substring(start, end)).trim();
    }

    /** 按规则所属维度给出成因提示 */
    private static String causeHint(String ruleId) {
        String prefix = categoryOf(ruleId);
        return switch (prefix) {
            case "BUG" -> "这类缺陷通常在异常路径或边界条件下触发，正常流程测试不容易覆盖。";
            case "SECURITY" -> "安全问题的危害取决于暴露面，即使当前调用路径安全，也应消除该模式以防后续被复用。";
            case "PERFORMANCE" -> "该写法在数据量小的时候没有明显影响，随规模增长会线性甚至指数级放大。";
            case "STYLE" -> "该问题不影响功能正确性，但会持续抬高后续阅读与修改的成本。";
            default -> "建议结合上下文确认该处代码的实际意图。";
        };
    }

    /** 按规则 ID 给出修复方向 */
    private static String fixHint(String ruleId) {
        return switch (ruleId) {
            case "BUG.EMPTY_CATCH" -> "记录日志或保留注释说明为何可以忽略。";
            case "BUG.RETURN_IN_FINALLY" -> "把返回值存入局部变量，在 finally 之后返回。";
            case "BUG.RESOURCE_LEAK" -> "改用 try-with-resources 声明资源。";
            case "BUG.STRING_EQ_OPERATOR" -> "改用 equals 或 Objects.equals。";
            case "BUG.BIGDECIMAL_EQUALS" -> "改用 compareTo(...) == 0。";
            case "BUG.OPTIONAL_GET" -> "改用 orElse / orElseThrow。";
            case "BUG.STATIC_DATE_FORMAT" -> "改用不可变的 DateTimeFormatter。";
            case "BUG.DIVIDE_BY_ZERO" -> "校验除数，或确认是否应使用浮点运算。";
            case "SEC.SQL_INJECTION" -> "改用 PreparedStatement 的参数占位符。";
            case "SEC.HARDCODED_CREDENTIAL" -> "迁移到环境变量或配置中心，并轮换已泄露的凭证。";
            case "SEC.WEAK_HASH" -> "改用 SHA-256 及以上；口令存储使用 bcrypt/argon2。";
            case "SEC.TRUST_ALL_CERT" -> "删除自定义 TrustManager，使用系统默认信任链。";
            case "SEC.ECB_MODE" -> "改用 AES/GCM/NoPadding 并传入随机 IV。";
            case "PERF.DB_IN_LOOP" -> "把查询提到循环外做批量查询，再在内存中匹配。";
            case "PERF.STRING_CONCAT_IN_LOOP" -> "改用 StringBuilder 并在循环外声明。";
            case "PERF.PATTERN_COMPILE_IN_LOOP" -> "把 Pattern 提为 static final 常量。";
            case "PERF.CONTAINS_IN_LOOP" -> "改用 HashSet 做存在性判断。";
            case "STYLE.METHOD_TOO_LONG" -> "按职责拆分为多个小方法。";
            case "STYLE.COMPLEXITY_TOO_HIGH" -> "抽取条件分支，或用卫语句提前返回。";
            case "STYLE.DEEP_NESTING" -> "用卫语句提前返回，降低嵌套层级。";
            case "STYLE.CONSTANT_NAMING" -> "改为全大写加下划线的常量命名。";
            default -> "参照规则说明调整该处代码。";
        };
    }

    private static String categoryOf(String ruleId) {
        int dot = ruleId.indexOf('.');
        return dot < 0 ? "" : ruleId.substring(0, dot).toUpperCase(Locale.ROOT);
    }
}
