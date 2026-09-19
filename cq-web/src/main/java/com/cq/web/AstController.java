package com.cq.web;

import com.cq.common.Result;
import com.cq.common.ast.AstSummary;
import com.cq.parser.AstParserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * AST 解析接口
 * <p>
 * 把解析能力单独暴露出来，便于调试规则与核查某个文件的调用图/复杂度。
 */
@RestController
@RequestMapping("/api/ast")
public class AstController {

    private final AstParserService parserService = new AstParserService();

    /**
     * 解析源码文本
     * @param body 含 source 字段的请求体
     * @return AST 摘要
     */
    @PostMapping("/parse")
    public Result<AstSummary> parse(@RequestBody Map<String, String> body) {
        String source = body.get("source");
        if (source == null || source.isBlank()) {
            return Result.error("缺少 source 字段");
        }
        return Result.success(parserService.parse(source));
    }

    /**
     * 解析服务器本地文件
     * @param path 文件路径
     * @return AST 摘要
     */
    @GetMapping("/file")
    public Result<AstSummary> parseFile(@RequestParam String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            return Result.error("文件不存在: " + path);
        }
        return Result.success(parserService.parseFile(file));
    }
}
