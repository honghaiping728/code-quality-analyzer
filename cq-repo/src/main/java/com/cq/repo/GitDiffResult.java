package com.cq.repo;

import java.util.ArrayList;
import java.util.List;

/**
 * Git Diff 结果封装
 * 记录变更的文件、新增/删除行数、以及具体的变更内容
 */
public class GitDiffResult {
    private String filePath;
    private int addedLines;
    private int deletedLines;
    private List<String> diffContent = new ArrayList<>();

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getAddedLines() { return addedLines; }
    public void setAddedLines(int addedLines) { this.addedLines = addedLines; }

    public int getDeletedLines() { return deletedLines; }
    public void setDeletedLines(int deletedLines) { this.deletedLines = deletedLines; }

    public List<String> getDiffContent() { return diffContent; }
    public void setDiffContent(List<String> diffContent) { this.diffContent = diffContent; }

    @Override
    public String toString() {
        return "GitDiffResult{" +
                "filePath='" + filePath + '\'' +
                ", addedLines=" + addedLines +
                ", deletedLines=" + deletedLines +
                '}';
    }
}
