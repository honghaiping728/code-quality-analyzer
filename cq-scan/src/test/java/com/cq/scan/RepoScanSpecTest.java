package com.cq.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓库扫描请求归一化与校验测试
 * <p>
 * {@code of()} 是用户输入进入系统的第一道关卡：git@ 形式要能转成 https（否则 JVM
 * 侧需要 SSH 依赖）、非法模式要在入口拦下（否则任务建了却没人认识它）、
 * FULL 模式下残留的提交号要清掉（否则数据库里躺着误导性的区间数据）。
 */
class RepoScanSpecTest {

    @Test
    @DisplayName("https 与 git@ 地址都归一化为 canonical 形式")
    void normalizesUrl() {
        assertEquals("https://github.com/owner/repo",
                RepoScanSpec.of("git@github.com:owner/repo.git", null, null, null, null).url());
        assertEquals("https://github.com/owner/repo",
                RepoScanSpec.of("https://github.com/owner/repo.git/", null, null, null, null).url());
    }

    @Test
    @DisplayName("模式缺省为 FULL，大小写不敏感")
    void normalizesMode() {
        assertEquals("FULL", RepoScanSpec.of("https://github.com/o/r", null, null, null, null).mode());
        assertEquals("FULL", RepoScanSpec.of("https://github.com/o/r", null, "  ", null, null).mode());
        assertEquals("INCREMENTAL",
                RepoScanSpec.of("https://github.com/o/r", null, "incremental", null, null).mode());
    }

    @Test
    @DisplayName("非法模式被拒绝且提示支持的模式")
    void rejectsUnknownMode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RepoScanSpec.of("https://github.com/o/r", null, "DELTA", null, null));
        assertTrue(error.getMessage().contains("FULL"),
                "错误信息应说明支持的模式，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("FULL 模式下即使传了提交号也会清空")
    void fullModeClearsCommits() {
        RepoScanSpec spec = RepoScanSpec.of("https://github.com/o/r", null, "FULL", "abc123", "def456");
        assertFalse(spec.incremental());
        assertNull(spec.baseCommit());
        assertNull(spec.headCommit());
    }

    @Test
    @DisplayName("INCREMENTAL 保留提交号，空白一律转 null 交由运行时解析")
    void incrementalKeepsCommits() {
        RepoScanSpec spec = RepoScanSpec.of("https://github.com/o/r", null, "INCREMENTAL", "abc123", " def456 ");
        assertTrue(spec.incremental());
        assertEquals("abc123", spec.baseCommit());
        assertEquals("def456", spec.headCommit());

        RepoScanSpec blank = RepoScanSpec.of("https://github.com/o/r", "  ", "INCREMENTAL", null, "  ");
        assertNull(blank.baseCommit());
        assertNull(blank.headCommit());
        assertNull(blank.branch());
    }

    @Test
    @DisplayName("非 GitHub 地址与空地址被拒绝")
    void rejectsInvalidUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> RepoScanSpec.of("https://gitlab.com/o/r.git", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RepoScanSpec.of(null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RepoScanSpec.of("  ", null, null, null, null));
    }
}
