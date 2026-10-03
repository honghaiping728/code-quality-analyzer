package com.cq.scan;

import com.cq.repo.GitHubRepoClient;

/**
 * 仓库扫描请求（归一化 + 校验后）
 * <p>
 * 由 {@link #of} 构造并承担全部参数校验：仓库地址解析为 {@code https://github.com/owner/repo}
 * 的规范形式（git@ 形式一并转换）、扫描模式白名单、增量提交号的清理。通过 of 得到的实例
 * 在后续链路中可直接使用，不必再判空或处理大小写。校验独立于 Spring，便于单测钉住。
 *
 * @param url 规范化的仓库地址（canonical https 形式）
 * @param branch 分支，可为 null（使用仓库默认分支）
 * @param mode FULL 全量 | INCREMENTAL 增量
 * @param baseCommit 增量基线提交，可为 null（运行时按 repo.last_commit_id → 父提交解析）
 * @param headCommit 增量目标提交，可为 null（默认分支/指定分支的最新提交）
 */
public record RepoScanSpec(String url, String branch, String mode, String baseCommit, String headCommit) {

    public static final String MODE_FULL = "FULL";
    public static final String MODE_INCREMENTAL = "INCREMENTAL";

    public RepoScanSpec {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("仓库地址不能为空");
        }
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("扫描模式不能为空");
        }
    }

    /**
     * 归一化并校验请求参数
     * @param url 仓库地址（https 或 git@ 形式）
     * @param branch 分支，可为空
     * @param mode 扫描模式，可为空（默认 FULL），仅接受 FULL / INCREMENTAL
     * @param baseCommit 增量基线提交，可为空
     * @param headCommit 增量目标提交，可为空
     * @return 归一化后的请求
     * @throws IllegalArgumentException 地址不合法、模式非法
     */
    public static RepoScanSpec of(String url, String branch, String mode, String baseCommit, String headCommit) {
        GitHubRepoClient.RepoRef ref = GitHubRepoClient.parse(url);
        String normalizedMode = (mode == null || mode.isBlank()) ? MODE_FULL : mode.trim().toUpperCase();
        if (!MODE_FULL.equals(normalizedMode) && !MODE_INCREMENTAL.equals(normalizedMode)) {
            throw new IllegalArgumentException("扫描模式仅支持 FULL（全量）或 INCREMENTAL（增量），当前：" + mode);
        }
        boolean incremental = MODE_INCREMENTAL.equals(normalizedMode);
        return new RepoScanSpec(ref.canonicalUrl(),
                blankToNull(branch),
                normalizedMode,
                incremental ? blankToNull(baseCommit) : null,
                incremental ? blankToNull(headCommit) : null);
    }

    /** 是否增量扫描 */
    public boolean incremental() {
        return MODE_INCREMENTAL.equals(mode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
