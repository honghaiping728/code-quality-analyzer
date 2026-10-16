package com.cq.parser;

import com.cq.common.ast.ParseProblems;
import com.cq.common.lang.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析器注册表测试
 * <p>
 * 两条主线：Java 仍走 AST（既有能力不能退化），配置类文件只做良构校验且
 * **合法写法不能被误判为损坏**。后者是本组测试的重点 —— 把 MyBatis 映射文件、
 * 多文档 YAML、大体积 lock 文件误报成「文件写坏了」，比漏报更伤规则的可信度。
 */
class SourceParserRegistryTest {

    private static final SourceParserRegistry REGISTRY = SourceParserRegistry.defaults();

    // ==================== 语言派发 ====================

    @Test
    @DisplayName("Java 仍走 AST，语言标记为 JAVA")
    void javaStillProducesAst() {
        ParsedFile parsed = REGISTRY.parse("package t; class A { void f() {} }", "src/A.java");
        assertEquals(Language.JAVA, parsed.getLanguage());
        assertTrue(parsed.hasUnit(), "Java 文件应当产出 CompilationUnit");
        assertEquals(1, parsed.getSummary().getMethods().size());
    }

    @Test
    @DisplayName("非 Java 文件走纯文本：没有 AST，但源码与行号定位可用")
    void plainTextKeepsSource() {
        ParsedFile parsed = REGISTRY.parse("curl x | sh\n", "deploy.sh");
        assertEquals(Language.SHELL, parsed.getLanguage());
        assertFalse(parsed.hasUnit(), "脚本不应产出 AST");
        assertEquals("curl x | sh", parsed.lineAt(1));
        assertEquals(2, parsed.lineCount());
    }

    @Test
    @DisplayName("识别不出的类型落到兜底解析器，语言为 UNKNOWN")
    void unknownFallsBackToPlainText() {
        ParsedFile parsed = REGISTRY.parse("binary-ish", "data.bin");
        assertEquals(Language.UNKNOWN, parsed.getLanguage());
        assertFalse(parsed.hasUnit());
    }

    // ==================== JSON ====================

    @Test
    @DisplayName("合法 JSON 不产生解析问题")
    void validJsonIsClean() {
        ParsedFile parsed = REGISTRY.parse("{\"a\": [1, 2], \"b\": {\"c\": true}}", "cfg.json");
        assertTrue(parsed.getSummary().isParsed(), parsed.getSummary().getParseErrors().toString());
    }

    @Test
    @DisplayName("JSON 语法错误被记录，且带上出错行号")
    void brokenJsonReportsLine() {
        ParsedFile parsed = REGISTRY.parse("{\n  \"a\": 1,\n  \"b\":\n}\n", "cfg.json");
        assertFalse(parsed.getSummary().isParsed());
        String problem = parsed.getSummary().getParseErrors().get(0);
        assertEquals(4, ParseProblems.lineOf(problem), "应当指向出错的第 4 行，实际：" + problem);
    }

    @Test
    @DisplayName("JSON 后面跟着多余内容也算错误")
    void trailingContentIsRejected() {
        ParsedFile parsed = REGISTRY.parse("{\"a\": 1} 以及一些多余内容", "cfg.json");
        assertFalse(parsed.getSummary().isParsed());
    }

    // ==================== YAML ====================

    @Test
    @DisplayName("多文档 YAML 是合法写法，不得误报")
    void multiDocumentYamlIsValid() {
        String source = """
                apiVersion: v1
                kind: Service
                ---
                apiVersion: apps/v1
                kind: Deployment
                """;
        ParsedFile parsed = REGISTRY.parse(source, "k8s.yaml");
        assertTrue(parsed.getSummary().isParsed(), parsed.getSummary().getParseErrors().toString());
    }

    @Test
    @DisplayName("YAML 缩进错误被记录并带行号")
    void brokenYamlReportsLine() {
        ParsedFile parsed = REGISTRY.parse("server:\n  port: 8080\n bad-indent: 1\n", "app.yml");
        assertFalse(parsed.getSummary().isParsed());
        assertTrue(ParseProblems.lineOf(parsed.getSummary().getParseErrors().get(0)) >= 3);
    }

    @Test
    @DisplayName("YAML 对象标签不会实例化任意类（SafeConstructor 生效）")
    void yamlTagsAreNotInstantiated() {
        ParsedFile parsed = REGISTRY.parse(
                "value: !!javax.script.ScriptEngineManager [!!java.net.URL [\"http://x\"]]", "evil.yml");
        assertFalse(parsed.getSummary().isParsed(), "带任意类标签的 YAML 应当被拒绝而不是被实例化");
    }

    // ==================== XML ====================

    @Test
    @DisplayName("带 DOCTYPE 的 MyBatis 映射文件是合法的，不得误报")
    void mybatisMapperWithDoctypeIsValid() {
        String source = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="find">SELECT * FROM t WHERE id = #{id}</select>
                </mapper>
                """;
        ParsedFile parsed = REGISTRY.parse(source, "mapper/UserMapper.xml");
        assertTrue(parsed.getSummary().isParsed(), parsed.getSummary().getParseErrors().toString());
    }

    @Test
    @DisplayName("XML 不解析外部实体（XXE 防护生效）")
    void xmlExternalEntityIsNotResolved() {
        String source = """
                <?xml version="1.0"?>
                <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """;
        ParsedFile parsed = REGISTRY.parse(source, "attack.xml");
        assertTrue(parsed.getSummary().isParsed(), "不解析外部实体时文档本身仍是良构的");
        assertFalse(parsed.getSummary().getParseErrors().toString().contains("root:"),
                "解析结果中不应出现被读取的文件内容");
    }

    @Test
    @DisplayName("XML 标签不闭合被记录")
    void brokenXmlIsReported() {
        ParsedFile parsed = REGISTRY.parse("<root><child></root>", "broken.xml");
        assertFalse(parsed.getSummary().isParsed());
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("超出体积上限的大文件跳过校验，而不是判为损坏")
    void oversizedFileIsSkippedNotFlagged() {
        StringBuilder huge = new StringBuilder("{\"data\": [");
        while (huge.length() < StructuredSourceParser.MAX_VALIDATED_CHARS + 1024) {
            huge.append("1,");
        }
        huge.append("0]}");
        ParsedFile parsed = REGISTRY.parse(huge.toString(), "package-lock.json");
        assertTrue(parsed.getSummary().isParsed(), "跳过校验不应产生解析问题");
    }

    @Test
    @DisplayName("空文件不产生解析问题")
    void blankFileIsClean() {
        assertTrue(REGISTRY.parse("", "empty.yml").getSummary().isParsed());
        assertTrue(REGISTRY.parse("   \n\n", "blank.yml").getSummary().isParsed());
    }

    @Test
    @DisplayName("解析器可并发调用（扫描按文件多线程执行）")
    void isSafeForConcurrentUse() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ParsedFile>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                tasks.add(() -> switch (index % 4) {
                    case 0 -> REGISTRY.parse("package t; class A { void f() {} }", "A.java");
                    case 1 -> REGISTRY.parse("a: 1\nb: 2\n", "cfg.yml");
                    case 2 -> REGISTRY.parse("{\"a\": 1}", "cfg.json");
                    default -> REGISTRY.parse("<root><child/></root>", "cfg.xml");
                });
            }
            List<Future<ParsedFile>> futures = pool.invokeAll(tasks);
            for (Future<ParsedFile> future : futures) {
                assertTrue(future.get().getSummary().isParsed());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("支持的语言列表不含 UNKNOWN 且包含 Java")
    void supportedLanguagesAreListed() {
        assertTrue(REGISTRY.supportedLanguages().contains(Language.JAVA));
        assertTrue(REGISTRY.supportedLanguages().contains(Language.YAML));
        assertFalse(REGISTRY.supportedLanguages().contains(Language.UNKNOWN));
    }
}
