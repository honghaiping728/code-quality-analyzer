package com.cq.web;

import com.cq.common.Result;
import com.cq.repo.GitDiffResult;
import com.cq.repo.GitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库接入接口
 * <p>
 * 提供克隆与 Diff 的自测入口，用于验证 JGit 链路是否正常。
 */
@RestController
@RequestMapping("/api/git")
public class GitTestController {

    private final GitService gitService;

    public GitTestController(GitService gitService) {
        this.gitService = gitService;
    }

    /**
     * 克隆仓库到本地
     * @param url 远程仓库地址
     * @param path 本地路径
     * @return 操作结果
     */
    @GetMapping("/clone")
    public Result<String> clone(@RequestParam(defaultValue = "https://github.com/octocat/Hello-World.git") String url,
                                @RequestParam(defaultValue = "/tmp/test-repo") String path) {
        try {
            gitService.cloneRepository(url, path);
            return Result.success("克隆成功: " + path);
        } catch (Exception e) {
            return Result.error("克隆失败: " + e.getMessage());
        }
    }

    /**
     * 获取两次提交之间的 Diff
     * @param path 本地仓库路径
     * @param oldCommit 旧提交
     * @param newCommit 新提交
     * @return 变更列表
     */
    @GetMapping("/diff")
    public Result<List<GitDiffResult>> diff(@RequestParam(defaultValue = "/tmp/test-repo") String path,
                                            @RequestParam(defaultValue = "HEAD~1") String oldCommit,
                                            @RequestParam(defaultValue = "HEAD") String newCommit) {
        try {
            List<GitDiffResult> diff = gitService.getDiff(path, oldCommit, newCommit);
            return Result.success(diff);
        } catch (Exception e) {
            return Result.error("Diff 获取失败: " + e.getMessage());
        }
    }
}
