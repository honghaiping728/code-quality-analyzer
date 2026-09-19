package com.cq.agent;

/**
 * Code Agent 的分析结果
 */
public class AgentResult {

    private String cause;          // 成因解释
    private String suggestion;     // 修复建议
    private double confidence;     // 调整后的置信度
    private String model;
    private boolean fromLlm;       // 是否来自真实大模型

    public AgentResult() {}

    public String getCause() { return cause; }
    public void setCause(String cause) { this.cause = cause; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isFromLlm() { return fromLlm; }
    public void setFromLlm(boolean fromLlm) { this.fromLlm = fromLlm; }

    @Override
    public String toString() {
        return "AgentResult{model=" + model + ", fromLlm=" + fromLlm
                + ", confidence=" + confidence + "}";
    }
}
