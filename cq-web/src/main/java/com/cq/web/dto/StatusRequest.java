package com.cq.web.dto;

/**
 * 更新问题状态的请求体
 */
public class StatusRequest {

    private String status;   // OPEN | CONFIRMED | FALSE_POSITIVE | FIXED

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
