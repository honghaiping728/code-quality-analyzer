package com.cq.web;

import com.cq.admin.RuleConfigService;
import com.cq.common.Result;
import com.cq.common.model.IgnoreEntry;
import com.cq.common.model.RuleConfig;
import com.cq.common.model.RuleThreshold;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 规则、阈值与忽略列表管理接口
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleConfigService ruleConfigService;

    public RuleController(RuleConfigService ruleConfigService) {
        this.ruleConfigService = ruleConfigService;
    }

    /**
     * 规则列表（合并内置元数据与数据库中的启停/覆盖配置）
     * @return 规则配置列表
     */
    @GetMapping
    public Result<List<RuleConfig>> list() {
        return Result.success(ruleConfigService.listRules());
    }

    /**
     * 更新规则配置
     * @param ruleId 规则 ID
     * @param update 变更内容
     * @return 更新后的配置
     */
    @PatchMapping("/{ruleId}")
    public Result<RuleConfig> update(@PathVariable String ruleId, @RequestBody RuleConfig update) {
        update.setRuleId(ruleId);
        return Result.success(ruleConfigService.updateRule(update));
    }

    /**
     * 阈值配置列表
     * @return 阈值配置行
     */
    @GetMapping("/thresholds")
    public Result<List<RuleThreshold>> thresholds() {
        return Result.success(ruleConfigService.listThresholds());
    }

    /**
     * 更新单条阈值
     * @param key 配置键
     * @param body 含 value 字段的请求体
     * @return 操作结果
     */
    @PatchMapping("/thresholds/{key}")
    public Result<String> updateThreshold(@PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body.get("value");
        if (value == null) {
            return Result.error("缺少 value 字段");
        }
        return ruleConfigService.updateThreshold(key, value)
                ? Result.success("已更新")
                : Result.error("配置项不存在: " + key);
    }

    /**
     * 忽略列表
     * @return 忽略项
     */
    @GetMapping("/ignores")
    public Result<List<IgnoreEntry>> ignores() {
        return Result.success(ruleConfigService.listIgnores());
    }

    /**
     * 新增忽略项
     * @param entry 忽略项
     * @return 已保存的忽略项
     */
    @PostMapping("/ignores")
    public Result<IgnoreEntry> addIgnore(@RequestBody IgnoreEntry entry) {
        if (entry.getFilePattern() == null || entry.getFilePattern().isBlank()) {
            return Result.error("filePattern 不能为空");
        }
        return Result.success(ruleConfigService.addIgnore(entry));
    }

    /**
     * 删除忽略项
     * @param id 忽略项 ID
     * @return 操作结果
     */
    @DeleteMapping("/ignores/{id}")
    public Result<String> deleteIgnore(@PathVariable Long id) {
        return ruleConfigService.deleteIgnore(id) ? Result.success("已删除") : Result.error("忽略项不存在");
    }

    /**
     * 重新加载配置缓存
     * @return 操作结果
     */
    @PostMapping("/reload")
    public Result<String> reload() {
        ruleConfigService.reload();
        return Result.success("配置已重新加载");
    }
}
