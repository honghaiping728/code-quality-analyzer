package com.cq.suggestion;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修复模板库
 * <p>
 * 对语法模式固定的问题直接套模板产出修复代码，不走大模型：这类修复是确定性的，
 * 用模型反而更慢、更贵且不稳定。只有模板覆盖不到的语义级问题才交给 Code Agent。
 * <p>
 * 模板只做**局部替换**（通常是单行），因此置信度可以给高；跨语句的复杂重构不做。
 */
public final class FixTemplates {

    private FixTemplates() {}

    /** 单行修复：输入原始行，输出修复后行；不适用时返回 null */
    @FunctionalInterface
    public interface LineFix {
        String apply(String line);
    }

    private static final Map<String, LineFix> TEMPLATES = Map.ofEntries(
            Map.entry("BUG.PRINT_STACK_TRACE", line -> replacePrintStackTrace(line)),
            Map.entry("BUG.STRING_EQ_OPERATOR", line -> rewriteLiteralEquality(line)),
            Map.entry("BUG.BIGDECIMAL_EQUALS", line -> rewriteBigDecimalEquals(line)),
            Map.entry("BUG.SELF_COMPARISON", line -> null),
            Map.entry("BUG.DIVIDE_BY_ZERO", line -> null),
            Map.entry("PERF.PATTERN_COMPILE_IN_LOOP", line -> null),
            Map.entry("STYLE.CONSTANT_NAMING", line -> null),
            Map.entry("STYLE.SHORT_FIELD_NAME", line -> null));

    /**
     * 取指定规则的修复模板
     * @param ruleId 规则 ID
     * @return 模板，无对应模板时返回 null
     */
    public static LineFix templateFor(String ruleId) {
        return TEMPLATES.get(ruleId);
    }

    /** 是否已有确定性模板 */
    public static boolean hasTemplate(String ruleId) {
        return TEMPLATES.containsKey(ruleId);
    }

    /**
     * e.printStackTrace() → log.error("...", e)
     * <p>
     * 仅替换调用本身，日志语句留给开发者补上下文。
     */
    private static String replacePrintStackTrace(String line) {
        if (!line.contains("printStackTrace()")) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\w+)\\.printStackTrace\\(\\);?").matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String variable = matcher.group(1);
        return line.replace(matcher.group(),
                "log.error(\"处理失败\", " + variable + ");");
    }

    /** s == "x" → "x".equals(s)，同时处理 s != "x" */
    private static String rewriteLiteralEquality(String line) {
        Matcher equals = Pattern.compile("([\\w.]+)\\s*==\\s*(\"[^\"]*\")").matcher(line);
        if (equals.find()) {
            return line.replace(equals.group(), equals.group(2) + ".equals(" + equals.group(1) + ")");
        }
        Matcher notEquals = Pattern.compile("([\\w.]+)\\s*!=\\s*(\"[^\"]*\")").matcher(line);
        if (notEquals.find()) {
            return line.replace(notEquals.group(),
                    "!" + notEquals.group(2) + ".equals(" + notEquals.group(1) + ")");
        }
        return null;
    }

    /** a.equals(b) → a.compareTo(b) == 0 */
    private static String rewriteBigDecimalEquals(String line) {
        Matcher matcher = Pattern.compile("([\\w.]+)\\.equals\\(([\\w.]+)\\)").matcher(line);
        if (matcher.find()) {
            return line.replace(matcher.group(),
                    matcher.group(1) + ".compareTo(" + matcher.group(2) + ") == 0");
        }
        return null;
    }
}
