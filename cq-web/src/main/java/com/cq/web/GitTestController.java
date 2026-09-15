package com.cq.web;

import com.cq.repo.GitDiffResult;
import com.cq.repo.GitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/git")
public class GitTestController {

    @GetMapping("/clone")
    public String testClone() {
        try {
            GitService gitService = new GitService();
            // 克隆一个公开的测试仓库到本地 /tmp/test-repo
            gitService.cloneRepository("https://github.com/octocat/Hello-World.git", "/tmp/test-repo");
            return "克隆成功！";
        } catch (Exception e) {
            e.printStackTrace();
            return "克隆失败: " + e.getMessage();
        }
    }

    @GetMapping("/diff")
    public String testDiff() {
        try {
            GitService gitService = new GitService();
            List<GitDiffResult> diff = gitService.getDiff("/tmp/test-repo", "HEAD~1", "HEAD");
            return "Diff 获取成功，变更文件数：" + diff.size() + "，第一个文件：" + (diff.isEmpty() ? "无" : diff.get(0).getFilePath());
        } catch (Exception e) {
            e.printStackTrace();
            return "Diff 获取失败: " + e.getMessage();
        }
    }
}
