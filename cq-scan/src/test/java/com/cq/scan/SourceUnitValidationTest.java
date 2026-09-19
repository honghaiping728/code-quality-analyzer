package com.cq.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传/粘贴内容校验测试
 * <p>
 * 这些输入来自浏览器，是整条链路上唯一的不可信来源。上限必须生效，
 * 否则一次超大上传就能把服务拖垮。
 */
class SourceUnitValidationTest {

    @Test
    @DisplayName("空列表被拒绝")
    void rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> ScanService.validateUnits(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ScanService.validateUnits(null));
    }

    @Test
    @DisplayName("文件数超过上限被拒绝")
    void rejectsTooManyFiles() {
        List<SourceUnit> units = new ArrayList<>();
        for (int i = 0; i <= ScanService.MAX_UPLOAD_FILES; i++) {
            units.add(new SourceUnit("F" + i + ".java", "class F" + i + " {}"));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScanService.validateUnits(units));
        assertTrue(error.getMessage().contains("最多"), "错误信息应说明上限，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("单个文件超过体积上限被拒绝")
    void rejectsOversizedSource() {
        String huge = "x".repeat(ScanService.MAX_SOURCE_CHARS + 1);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScanService.validateUnits(List.of(new SourceUnit("Big.java", huge))));
        assertTrue(error.getMessage().contains("Big.java"),
                "错误信息应指出是哪个文件，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("正常规模的内容通过校验")
    void acceptsReasonableInput() {
        assertDoesNotThrow(() -> ScanService.validateUnits(List.of(
                new SourceUnit("A.java", "class A {}"),
                new SourceUnit("B.java", "class B {}"))));
    }

    @Test
    @DisplayName("恰好等于上限时通过（边界不差一）")
    void acceptsExactlyAtLimit() {
        List<SourceUnit> units = new ArrayList<>();
        for (int i = 0; i < ScanService.MAX_UPLOAD_FILES; i++) {
            units.add(new SourceUnit("F" + i + ".java", "class F" + i + " {}"));
        }
        assertDoesNotThrow(() -> ScanService.validateUnits(units));

        String exact = "x".repeat(ScanService.MAX_SOURCE_CHARS);
        assertDoesNotThrow(() -> ScanService.validateUnits(List.of(new SourceUnit("Exact.java", exact))));
    }

    @Test
    @DisplayName("空白内容被识别为无需分析")
    void detectsBlankContent() {
        assertTrue(new SourceUnit("Empty.java", "   \n  ").isBlank());
        assertTrue(new SourceUnit("Null.java", null).isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(new SourceUnit("Ok.java", "class A {}").isBlank());
    }
}
