package com.cq.repo;

/**
 * GitHub 在线访问失败
 * <p>
 * message 直接面向使用者（中文、可操作），会被写入扫描任务的 errorMessage 展示到控制台，
 * 因此**不允许**夹带 token、请求头或服务器本地路径等内部信息；排查细节通过 cause 与日志查看。
 */
public class GitHubApiException extends RuntimeException {

    public GitHubApiException(String message) {
        super(message);
    }

    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
