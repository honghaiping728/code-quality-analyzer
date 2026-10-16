package com.cq.scan;

import com.cq.common.model.RuleConfigSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓库在线文件筛选测试
 * <p>
 * trees / compare 接口返回的是路径清单，挑错或漏挑都会直接影响扫描结果：
 * 不支持的类型混进来是噪音，target/generated/node_modules 漏掉是白费解析开销，
 * 上限失效则可能让一次扫描拖垮服务。这里用默认阈值（无 DB）钉住筛选语义。
 */
class RepoScanSelectionTest {

    /** 默认阈值：忽略 target、generated、node_modules 等目录 */
    private static final RuleConfigSet CONFIG = new RuleConfigSet();

    @Test
    @DisplayName("保留全部受支持类型，识别不出的扩展名被过滤")
    void keepsSupportedTypesOnly() {
        List<String> result = ScanService.filterSourcePaths(List.of(
                "src/main/java/A.java",
                "config/application.yml",
                "pom.xml",
                "web/app.vue",
                "db/schema.sql",
                "Dockerfile",
                "assets/logo.png",
                "lib/util.class",
                "gradle/wrapper.jar",
                "LICENSE"), CONFIG, 100);
        assertEquals(List.of(
                "Dockerfile",
                "config/application.yml",
                "db/schema.sql",
                "pom.xml",
                "src/main/java/A.java",
                "web/app.vue"), result);
    }

    @Test
    @DisplayName("忽略 glob 生效：target、generated 与 node_modules 目录被跳过")
    void appliesIgnorePatterns() {
        List<String> result = ScanService.filterSourcePaths(List.of(
                "src/main/java/A.java",
                "target/generated-sources/B.java",
                "module/target/C.java",
                "build/generated/D.java",
                "web/node_modules/pkg/index.js",
                "web/dist/bundle.js",
                "web/static/app.min.js"), CONFIG, 100);
        assertEquals(List.of("src/main/java/A.java"), result);
    }

    @Test
    @DisplayName("重复路径去重且按字典序排序（保证扫描结果稳定）")
    void dedupesAndSorts() {
        List<String> result = ScanService.filterSourcePaths(List.of(
                "b/B.java",
                "a/A.java",
                "b/B.java"), CONFIG, 100);
        assertEquals(List.of("a/A.java", "b/B.java"), result);
    }

    @Test
    @DisplayName("超过文件数上限直接失败而不是静默截断")
    void rejectsOverLimit() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            paths.add("F" + i + ".java");
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScanService.filterSourcePaths(paths, CONFIG, 10));
        assertTrue(error.getMessage().contains("cq.repo.max-files"),
                "错误信息应提示调大配置项，实际：" + error.getMessage());
    }

    @Test
    @DisplayName("空输入与 null 条目安全跳过")
    void handlesEmptyAndNullEntries() {
        assertTrue(ScanService.filterSourcePaths(List.of(), CONFIG, 100).isEmpty());
        assertTrue(ScanService.filterSourcePaths(null, CONFIG, 100).isEmpty());
        assertEquals(List.of("A.java"),
                ScanService.filterSourcePaths(Arrays.asList("A.java", null), CONFIG, 100));
    }

    @Test
    @DisplayName("反斜杠路径被归一化为 /")
    void normalizesBackslashes() {
        assertEquals(List.of("src/A.java"),
                ScanService.filterSourcePaths(List.of("src\\A.java"), CONFIG, 100));
    }
}
