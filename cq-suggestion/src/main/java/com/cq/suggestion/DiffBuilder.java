package com.cq.suggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单统一 diff 生成器
 * <p>
 * 只实现单行替换场景所需的「最小可用 diff」：修复建议本质是「把这一行换成另一行」，
 * 不需要完整的 Myers 差分算法。避免为此引入额外依赖。
 */
public final class DiffBuilder {

    private DiffBuilder() {}

    /**
     * 生成单行替换的 diff
     * @param filePath 文件路径
     * @param lineNumber 行号（1 起）
     * @param original 原始行
     * @param fixed 修复后行
     * @return 统一 diff 文本
     */
    public static String forSingleLine(String filePath, int lineNumber, String original, String fixed) {
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(nullSafe(filePath)).append('\n');
        diff.append("+++ b/").append(nullSafe(filePath)).append('\n');
        diff.append("@@ -").append(lineNumber).append(" +").append(lineNumber).append(" @@\n");
        diff.append('-').append(nullSafe(original)).append('\n');
        diff.append('+').append(nullSafe(fixed));
        return diff.toString();
    }

    /**
     * 生成多行替换的 diff
     * @param filePath 文件路径
     * @param startLine 起始行号
     * @param originalLines 原始行
     * @param fixedLines 修复后行
     * @return 统一 diff 文本
     */
    public static String forBlock(String filePath, int startLine,
                                  List<String> originalLines, List<String> fixedLines) {
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(nullSafe(filePath)).append('\n');
        diff.append("+++ b/").append(nullSafe(filePath)).append('\n');
        diff.append("@@ -").append(startLine).append(',').append(originalLines.size())
                .append(" +").append(startLine).append(',').append(fixedLines.size()).append(" @@\n");
        for (String line : originalLines) {
            diff.append('-').append(nullSafe(line)).append('\n');
        }
        for (String line : fixedLines) {
            diff.append('+').append(nullSafe(line)).append('\n');
        }
        return diff.toString().trim();
    }

    /**
     * 缩进感知的单行替换：保持原有前导空白
     * @param original 原始行
     * @param replacement 替换后的内容（不含缩进）
     * @return 保留缩进的替换行
     */
    public static String keepIndent(String original, String replacement) {
        if (original == null) {
            return replacement;
        }
        int index = 0;
        while (index < original.length() && Character.isWhitespace(original.charAt(index))) {
            index++;
        }
        return original.substring(0, index) + replacement;
    }

    /** 把整段代码切成行，便于展示 */
    public static List<String> toLines(String code) {
        if (code == null || code.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(code.split("\n", -1)));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
