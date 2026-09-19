package com.cq.common.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 修复建议
 * <p>
 * 由 cq-suggestion 生成：模板规则直接产出，语义级问题交由 Code Agent 生成。
 * {@link #confidence} 低于阈值时前端标注「供参考」。
 */
public class FixSuggestion implements Serializable {

    private Long id;
    private Long issueId;           // 关联问题
    private String originalCode;    // 修改前代码
    private String fixedCode;       // 修改后代码
    private String diff;            // 统一 diff 文本
    private String explanation;     // 修复说明
    private String source;          // TEMPLATE | LLM
    private double confidence;      // 0.0 - 1.0
    private boolean verified;       // 是否通过编译/测试验证
    private String verifyOutput;    // 验证输出
    private Date createTime;

    public FixSuggestion() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getIssueId() { return issueId; }
    public void setIssueId(Long issueId) { this.issueId = issueId; }

    public String getOriginalCode() { return originalCode; }
    public void setOriginalCode(String originalCode) { this.originalCode = originalCode; }

    public String getFixedCode() { return fixedCode; }
    public void setFixedCode(String fixedCode) { this.fixedCode = fixedCode; }

    public String getDiff() { return diff; }
    public void setDiff(String diff) { this.diff = diff; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String getVerifyOutput() { return verifyOutput; }
    public void setVerifyOutput(String verifyOutput) { this.verifyOutput = verifyOutput; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "FixSuggestion{issue=" + issueId + ", source=" + source
                + ", confidence=" + confidence + ", verified=" + verified + "}";
    }
}
