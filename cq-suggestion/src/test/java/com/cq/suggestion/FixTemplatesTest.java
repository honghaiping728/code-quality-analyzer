package com.cq.suggestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修复模板测试
 * <p>
 * 模板的价值在于产出的修复是确定性的，不依赖大模型。这里锁定的是
 * 「模板必须能匹配到规则实际给出的代码片段」—— 一旦规则报告的位置变了、
 * 导致片段不再包含待修复的调用，模板会静默失效并退化成调用大模型，
 * 这种回归不会报错，只会让成本和延迟悄悄上升。
 */
class FixTemplatesTest {

    @Test
    @DisplayName("printStackTrace 模板能改写成日志调用并保留缩进")
    void rewritesPrintStackTrace() {
        FixTemplates.LineFix fix = FixTemplates.templateFor("BUG.PRINT_STACK_TRACE");
        assertNotNull(fix, "该规则应配有确定性模板");

        String fixed = fix.apply("        e.printStackTrace();");
        assertNotNull(fixed, "模板应匹配到 printStackTrace 调用");
        assertTrue(fixed.contains("log.error"), "应改写为日志调用，实际：" + fixed);
        assertTrue(fixed.startsWith("        "), "应保留原有缩进，实际：" + fixed);
        assertFalse(fixed.contains("printStackTrace"), "不应残留原调用");
    }

    @Test
    @DisplayName("字符串 == 字面量模板能改写成 equals")
    void rewritesStringEquality() {
        FixTemplates.LineFix fix = FixTemplates.templateFor("BUG.STRING_EQ_OPERATOR");
        assertNotNull(fix);

        String fixed = fix.apply("        return status == \"ACTIVE\";");
        assertNotNull(fixed);
        assertTrue(fixed.contains("\"ACTIVE\".equals(status)"), "实际：" + fixed);
    }

    @Test
    @DisplayName("BigDecimal.equals 模板能改写成 compareTo")
    void rewritesBigDecimalEquals() {
        FixTemplates.LineFix fix = FixTemplates.templateFor("BUG.BIGDECIMAL_EQUALS");
        assertNotNull(fix);

        String fixed = fix.apply("        return a.equals(b);");
        assertNotNull(fixed);
        assertTrue(fixed.contains("compareTo(b) == 0"), "实际：" + fixed);
    }

    @Test
    @DisplayName("片段不匹配时模板返回 null，由上层回退到语义分析")
    void returnsNullWhenNoMatch() {
        FixTemplates.LineFix fix = FixTemplates.templateFor("BUG.PRINT_STACK_TRACE");
        assertNull(fix.apply("    } catch (Exception e) {"),
                "片段里没有 printStackTrace 时应返回 null，而不是产出错误的修复");
    }

    @Test
    @DisplayName("未配置模板的规则返回 null")
    void unknownRuleHasNoTemplate() {
        assertNull(FixTemplates.templateFor("STYLE.COMPLEXITY_TOO_HIGH"));
        assertFalse(FixTemplates.hasTemplate("SEC.SQL_INJECTION"));
    }

    @Test
    @DisplayName("单行 diff 结构正确")
    void buildsUnifiedDiff() {
        String diff = DiffBuilder.forSingleLine("src/A.java", 15, "e.printStackTrace();", "log.error(\"x\", e);");
        assertTrue(diff.contains("--- a/src/A.java"), diff);
        assertTrue(diff.contains("+++ b/src/A.java"), diff);
        assertTrue(diff.contains("@@ -15 +15 @@"), diff);
        assertTrue(diff.contains("-e.printStackTrace();"), diff);
        assertTrue(diff.contains("+log.error(\"x\", e);"), diff);
    }

    @Test
    @DisplayName("保留缩进的替换工具")
    void keepsIndentation() {
        // 八个空格的缩进应原样保留，只替换内容部分
        assertEquals("        replaced", DiffBuilder.keepIndent("        original", "replaced"));
        assertEquals("replaced", DiffBuilder.keepIndent("original", "replaced"));
    }
}
