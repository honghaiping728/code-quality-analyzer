package com.cq.common.lang;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言识别测试
 * <p>
 * 识别结果是整条链路的第一道闸门：判错会让文件走得进扫描却一个规则都不跑
 * （识别成 {@link Language#UNKNOWN}），或把二进制文件当成源码读进来。
 * 重点用例都在「看起来像但不一样」的边界上。
 */
class LanguageTest {

    @Test
    @DisplayName("按扩展名识别，且大小写不敏感")
    void recognizesByExtension() {
        assertEquals(Language.JAVA, Language.of("src/main/java/A.java"));
        assertEquals(Language.JAVA, Language.of("A.JAVA"));
        assertEquals(Language.YAML, Language.of("application.yml"));
        assertEquals(Language.YAML, Language.of("k8s/deploy.yaml"));
        assertEquals(Language.JSON, Language.of("package.json"));
        assertEquals(Language.SQL, Language.of("db/schema.sql"));
        assertEquals(Language.VUE, Language.of("src/views/Home.vue"));
        assertEquals(Language.PYTHON, Language.of("scripts/run.py"));
        assertEquals(Language.GO, Language.of("cmd/main.go"));
    }

    @Test
    @DisplayName("多段扩展名取最后一段：foo.spec.js 是 JavaScript 而非未知类型")
    void takesLastExtension() {
        assertEquals(Language.JAVASCRIPT, Language.of("tests/console.spec.js"));
        assertEquals(Language.TYPESCRIPT, Language.of("src/types/index.d.ts"));
    }

    @Test
    @DisplayName("pom.xml 按扩展名落到 XML")
    void pomIsXml() {
        assertEquals(Language.XML, Language.of("pom.xml"));
        assertEquals(Language.XML, Language.of("src/main/resources/mapper/UserMapper.xml"));
    }

    @Test
    @DisplayName("无扩展名的约定文件名：Dockerfile 与 Makefile 及其变体")
    void recognizesByFileName() {
        assertEquals(Language.DOCKERFILE, Language.of("Dockerfile"));
        assertEquals(Language.DOCKERFILE, Language.of("docker/dockerfile"));
        assertEquals(Language.DOCKERFILE, Language.of("Dockerfile.dev"));
        assertEquals(Language.DOCKERFILE, Language.of("api.dockerfile"));
        assertEquals(Language.DOCKERFILE, Language.of("Containerfile"));
        assertEquals(Language.MAKEFILE, Language.of("Makefile"));
        assertEquals(Language.MAKEFILE, Language.of("GNUmakefile"));
        assertEquals(Language.MAKEFILE, Language.of("Makefile.am"));
        assertEquals(Language.MAKEFILE, Language.of("rules.mk"));
    }

    @Test
    @DisplayName("隐藏文件：.env 及其变体属于配置文件，.gitignore 不属于")
    void handlesDotFiles() {
        assertEquals(Language.PROPERTIES, Language.of(".env"));
        assertEquals(Language.PROPERTIES, Language.of("config/.env"));
        assertEquals(Language.PROPERTIES, Language.of(".env.local"));
        assertEquals(Language.PROPERTIES, Language.of(".env.production"));
        // .gitignore 的点号在第 0 位，取出的 "gitignore" 不在扩展名表中
        assertEquals(Language.UNKNOWN, Language.of(".gitignore"));
        assertEquals(Language.UNKNOWN, Language.of(".dockerignore"));
    }

    @Test
    @DisplayName("Windows 分隔符与前后空白都能正确处理")
    void normalizesPathSeparators() {
        assertEquals(Language.JAVA, Language.of("C:\\work\\src\\A.java"));
        assertEquals(Language.YAML, Language.of("  config\\app.yml  "));
    }

    @Test
    @DisplayName("识别不出的类型一律返回 UNKNOWN，不抛异常")
    void unknownForUnrecognized() {
        assertEquals(Language.UNKNOWN, Language.of("LICENSE"));
        assertEquals(Language.UNKNOWN, Language.of("assets/logo.png"));
        assertEquals(Language.UNKNOWN, Language.of("lib/commons.jar"));
        assertEquals(Language.UNKNOWN, Language.of("gradle/wrapper/gradle-wrapper.properties.bak"));
        assertEquals(Language.UNKNOWN, Language.of("trailing."));
        assertEquals(Language.UNKNOWN, Language.of(""));
        assertEquals(Language.UNKNOWN, Language.of("   "));
        assertEquals(Language.UNKNOWN, Language.of((String) null));
        assertEquals(Language.UNKNOWN, Language.of("dir/"));
    }

    @Test
    @DisplayName("isScannable 只在 UNKNOWN 上为 false")
    void scannableExcludesUnknownOnly() {
        assertFalse(Language.UNKNOWN.isScannable());
        for (Language language : Language.values()) {
            assertEquals(language != Language.UNKNOWN, language.isScannable(), language.name());
        }
    }

    @Test
    @DisplayName("scannable 集合不含 UNKNOWN，且覆盖其余全部语言")
    void scannableSetCoversAllButUnknown() {
        assertEquals(Language.values().length - 1, Language.scannable().size());
        assertFalse(Language.scannable().contains(Language.UNKNOWN));
        assertTrue(Language.scannable().containsAll(
                java.util.EnumSet.complementOf(java.util.EnumSet.of(Language.UNKNOWN))));
    }
}
