package com.cq.agent;

/**
 * 大模型补全结果
 * <p>
 * 携带 token 用量与耗时，使大模型成本可度量（对应 README 的「量化成本节省」）。
 */
public class LlmResponse {

    private String content;
    private String model;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private long durationMs;
    private boolean success = true;
    private String errorMessage;

    public LlmResponse() {}

    /** 成功结果 */
    public static LlmResponse of(String content, String model, long durationMs) {
        LlmResponse response = new LlmResponse();
        response.content = content;
        response.model = model;
        response.durationMs = durationMs;
        return response;
    }

    /** 失败结果 */
    public static LlmResponse failure(String message, String model, long durationMs) {
        LlmResponse response = new LlmResponse();
        response.success = false;
        response.errorMessage = message;
        response.model = model;
        response.durationMs = durationMs;
        return response;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public String toString() {
        return "LlmResponse{model=" + model + ", tokens=" + totalTokens
                + ", cost=" + durationMs + "ms, ok=" + success + "}";
    }
}
