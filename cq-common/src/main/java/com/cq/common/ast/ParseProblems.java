package com.cq.common.ast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析问题的文本格式
 * <p>
 * 非 Java 文件只做良构校验，解析器拿到的错误信息（Jackson / SnakeYAML / SAX）
 * 除了原因之外还带行号，而 {@link AstSummary#getParseErrors()} 只是一个字符串列表，
 * 没有地方放行号。折中办法是把行号编进文本：{@code 第 12 行: 原因}。
 * <p>
 * 格式的生产者（cq-parser）与消费者（cq-rule 的 {@code BUG.INVALID_STRUCTURED_FILE}）
 * 都走本类，避免两边各写一份正则、改一处忘一处。
 */
public final class ParseProblems {

    private ParseProblems() {}

    /** 行号前缀，仅匹配本类自己生成的格式 */
    private static final Pattern LINE_PREFIX = Pattern.compile("^第 (\\d+) 行: ([\\s\\S]*)$");

    /**
     * 按约定格式拼装解析问题
     * @param line 行号（1 起），小于 1 时取 1
     * @param message 原始错误信息，可为 null
     * @return 形如 {@code 第 12 行: 原因} 的文本
     */
    public static String format(int line, String message) {
        return "第 " + Math.max(line, 1) + " 行: " + (message == null ? "语法错误" : message);
    }

    /**
     * 取出解析问题里的行号
     * @param problem 解析问题文本
     * @return 行号；文本不是本类格式时返回 1
     */
    public static int lineOf(String problem) {
        if (problem == null) {
            return 1;
        }
        Matcher matcher = LINE_PREFIX.matcher(problem);
        if (!matcher.matches()) {
            return 1;
        }
        try {
            return Math.max(Integer.parseInt(matcher.group(1)), 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 取出解析问题的原因部分
     * @param problem 解析问题文本
     * @return 去掉行号前缀的原因；文本不是本类格式时原样返回
     */
    public static String messageOf(String problem) {
        if (problem == null) {
            return "语法错误";
        }
        Matcher matcher = LINE_PREFIX.matcher(problem);
        return matcher.matches() ? matcher.group(2) : problem;
    }
}
