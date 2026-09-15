package com.cq.repo;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 仓库接入服务
 */
public class GitService {

    /**
     * 克隆远程仓库到本地
     * @param repoUrl 远程仓库地址
     * @param localPath 本地存放路径
     */
    public void cloneRepository(String repoUrl, String localPath) throws Exception {
        File localDir = new File(localPath);
        if (localDir.exists()) {
            System.out.println("目录已存在，跳过克隆: " + localPath);
            return;
        }
        System.out.println("正在克隆仓库: " + repoUrl + " -> " + localPath);
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(localDir)
                .call()) {
            System.out.println("克隆成功！");
        }
    }

    /**
     * 获取两个提交之间的 Diff
     * @param localPath 本地仓库路径
     * @param oldCommitId 旧提交 ID（可以是 HEAD~1）
     * @param newCommitId 新提交 ID（可以是 HEAD）
     * @return 变更列表
     */
    public List<GitDiffResult> getDiff(String localPath, String oldCommitId, String newCommitId) throws Exception {
        List<GitDiffResult> results = new ArrayList<>();

        File repoDir = new File(localPath, ".git");
        try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir).build();
             Git git = new Git(repository)) {

            ObjectId oldTreeId = repository.resolve(oldCommitId + "^{tree}");
            ObjectId newTreeId = repository.resolve(newCommitId + "^{tree}");

            AbstractTreeIterator oldTreeIter = prepareTreeParser(repository, oldTreeId);
            AbstractTreeIterator newTreeIter = prepareTreeParser(repository, newTreeId);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repository);
                List<DiffEntry> diffs = formatter.scan(oldTreeIter, newTreeIter);

                for (DiffEntry entry : diffs) {
                    GitDiffResult result = new GitDiffResult();
                    result.setFilePath(entry.getNewPath());

                    // 提取具体的 Diff 文本内容
                    formatter.format(entry);
                    String diffText = out.toString("UTF-8");
                    for (String line : diffText.split("\n")) {
                        result.getDiffContent().add(line);
                        if (line.startsWith("+") && !line.startsWith("+++")) {
                            result.setAddedLines(result.getAddedLines() + 1);
                        } else if (line.startsWith("-") && !line.startsWith("---")) {
                            result.setDeletedLines(result.getDeletedLines() + 1);
                        }
                    }
                    out.reset();
                    results.add(result);
                }
            }
        }
        return results;
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId treeId) throws Exception {
        try (org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
            tw.addTree(treeId);
            tw.setRecursive(true);
            return new CanonicalTreeParser(null, tw.getObjectReader(), treeId);
        }
    }
}
