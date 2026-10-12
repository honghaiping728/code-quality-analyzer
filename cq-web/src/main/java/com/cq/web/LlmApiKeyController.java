package com.cq.web;

import com.cq.agent.LlmApiKeyService;
import com.cq.agent.LlmApiKeyView;
import com.cq.common.Result;
import com.cq.common.model.LlmApiKey;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大模型 API Key 配置接口
 * <p>
 * 密钥只进不出：列表与新增的响应都是掩码视图 {@link LlmApiKeyView}，
 * 不提供任何回显密钥原文的接口。
 */
@RestController
@RequestMapping("/api/llm/keys")
public class LlmApiKeyController {

    private final LlmApiKeyService llmApiKeyService;

    public LlmApiKeyController(LlmApiKeyService llmApiKeyService) {
        this.llmApiKeyService = llmApiKeyService;
    }

    /** 配置列表（密钥为掩码） */
    @GetMapping
    public Result<List<LlmApiKeyView>> list() {
        return Result.success(llmApiKeyService.list());
    }

    /** 新增配置；表中尚无配置时自动启用 */
    @PostMapping
    public Result<LlmApiKeyView> add(@RequestBody LlmApiKey entry) {
        return Result.success(llmApiKeyService.add(entry));
    }

    /** 启用指定配置（立即生效，无需重启） */
    @PatchMapping("/{id}/activate")
    public Result<LlmApiKeyView> activate(@PathVariable Long id) {
        return Result.success(llmApiKeyService.activate(id));
    }

    /** 删除配置 */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        return llmApiKeyService.delete(id) ? Result.success("已删除") : Result.error("配置不存在");
    }
}
