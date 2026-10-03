package com.cq.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHubRepoClient 地址解析测试
 * <p>
 * 只覆盖纯函数：在线拉取需要网络与 GitHub 额度，不放进单测（由端到端验证覆盖）。
 * 地址解析是用户输入的第一道关卡，误判（把 GitLab 当 GitHub 放行、把 git@ 形式拒掉）
 * 会直接决定"一键仓库扫描"是否可用，因此各形式变体都要钉住。
 */
class GitHubRepoClientTest {

    @Test
    @DisplayName("https 地址解析为 owner/repo 并规范化为 canonical")
    void parsesHttpsUrl() {
        GitHubRepoClient.RepoRef ref = GitHubRepoClient.parse("https://github.com/owner/repo.git");
        assertEquals("owner", ref.owner());
        assertEquals("repo", ref.repo());
        assertEquals("https://github.com/owner/repo", ref.canonicalUrl());
    }

    @Test
    @DisplayName(".git 后缀、结尾斜杠、tree 尾巴、www 与 http 等变体均可解析")
    void parsesVariants() {
        assertEquals("repo", GitHubRepoClient.parse("https://github.com/owner/repo").repo());
        assertEquals("repo", GitHubRepoClient.parse("https://github.com/owner/repo/").repo());
        assertEquals("repo", GitHubRepoClient.parse("https://github.com/owner/repo/tree/dev").repo());
        assertEquals("repo", GitHubRepoClient.parse("https://github.com/owner/repo.git/").repo());
        assertEquals("repo", GitHubRepoClient.parse("http://github.com/owner/repo.git").repo());
        assertEquals("repo", GitHubRepoClient.parse("https://www.github.com/owner/repo.git").repo());
    }

    @Test
    @DisplayName("git@ 形式解析为 owner/repo 并转 https canonical（无需 SSH 依赖）")
    void parsesScpLikeUrl() {
        GitHubRepoClient.RepoRef ref = GitHubRepoClient.parse("git@github.com:owner/repo.git");
        assertEquals("owner", ref.owner());
        assertEquals("repo", ref.repo());
        assertEquals("https://github.com/owner/repo", ref.canonicalUrl());
    }

    @Test
    @DisplayName("域名与路径大小写混写时仍能解析")
    void parsesCaseInsensitively() {
        assertEquals("owner", GitHubRepoClient.parse("HTTPS://GITHUB.COM/owner/repo").owner());
    }

    @Test
    @DisplayName("非 GitHub 地址、空地址与含非法字符的地址被拒绝，且提示有用")
    void rejectsInvalidUrls() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse(null));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse("https://github.com/owner"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse("https://github.com/"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse("https://github.com/own er/repo"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse("https://github.com/owner/re po"));
        assertThrows(IllegalArgumentException.class, () -> GitHubRepoClient.parse("ftp://github.com/owner/repo"));

        IllegalArgumentException notGithub =
                assertThrows(IllegalArgumentException.class,
                        () -> GitHubRepoClient.parse("https://gitlab.com/owner/repo.git"));
        assertTrue(notGithub.getMessage().contains("GitHub"), "错误提示应说明仅支持 GitHub："
                + notGithub.getMessage());

        IllegalArgumentException otherSsh =
                assertThrows(IllegalArgumentException.class,
                        () -> GitHubRepoClient.parse("git@gitlab.com:owner/repo.git"));
        assertTrue(otherSsh.getMessage().contains("GitHub"));
    }
}
