package com.cq.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cq.admin.mapper.IgnoreEntryMapper;
import com.cq.admin.mapper.RuleConfigMapper;
import com.cq.admin.mapper.RuleThresholdMapper;
import com.cq.common.model.IgnoreEntry;
import com.cq.common.model.RuleConfig;
import com.cq.common.model.RuleConfigProvider;
import com.cq.common.model.RuleConfigSet;
import com.cq.common.model.RuleThreshold;
import com.cq.common.model.RuleThresholds;
import com.cq.rule.DefaultRules;
import com.cq.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则与阈值管理服务
 * <p>
 * 同时是 {@link RuleConfigProvider} 的实现：扫描模块只依赖 cq-common 里的接口，
 * 由本服务负责把数据库中的配置装配成 {@link RuleConfigSet}。
 * <p>
 * 配置读取带缓存并在写操作后失效 —— 每次扫描都重新查三张配置表没有必要。
 */
@Service
public class RuleConfigService implements RuleConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleConfigService.class);

    private final RuleConfigMapper ruleConfigMapper;
    private final RuleThresholdMapper thresholdMapper;
    private final IgnoreEntryMapper ignoreEntryMapper;

    private volatile RuleConfigSet cache;

    public RuleConfigService(RuleConfigMapper ruleConfigMapper,
                             RuleThresholdMapper thresholdMapper,
                             IgnoreEntryMapper ignoreEntryMapper) {
        this.ruleConfigMapper = ruleConfigMapper;
        this.thresholdMapper = thresholdMapper;
        this.ignoreEntryMapper = ignoreEntryMapper;
    }

    // ==================== RuleConfigProvider ====================

    @Override
    public RuleConfigSet current() {
        RuleConfigSet local = cache;
        if (local == null) {
            synchronized (this) {
                local = cache;
                if (local == null) {
                    local = load();
                    cache = local;
                }
            }
        }
        return local;
    }

    /** 清空缓存，下次读取重新装配（配置被修改后调用） */
    public void reload() {
        cache = null;
    }

    /** 从数据库装配完整配置 */
    private RuleConfigSet load() {
        RuleConfigSet configSet = new RuleConfigSet();
        try {
            Map<String, RuleConfig> rules = new LinkedHashMap<>();
            for (RuleConfig ruleConfig : ruleConfigMapper.selectList(null)) {
                rules.put(ruleConfig.getRuleId(), ruleConfig);
            }
            configSet.setRules(rules);

            RuleThresholds thresholds = new RuleThresholds();
            for (RuleThreshold row : thresholdMapper.selectList(null)) {
                applyThreshold(thresholds, row);
            }
            configSet.setThresholds(thresholds);

            LambdaQueryWrapper<IgnoreEntry> query = new LambdaQueryWrapper<>();
            query.eq(IgnoreEntry::isEnabled, true);
            configSet.setIgnoreEntries(ignoreEntryMapper.selectList(query));

        } catch (RuntimeException e) {
            // 数据库不可用时退回内置默认配置，而不是让扫描整体失败
            log.warn("读取规则配置失败，退回默认配置: {}", e.toString());
        }
        return configSet;
    }

    /**
     * 把一行阈值配置写进阈值对象
     * <p>
     * 用 switch 显式映射而非反射：配置键是外部输入，反射写字段容易在拼写错误时静默失败，
     * 显式映射能在编译期发现遗漏。
     */
    private static void applyThreshold(RuleThresholds thresholds, RuleThreshold row) {
        String key = row.getConfigKey();
        String value = row.getConfigValue();
        if (key == null || value == null) {
            return;
        }
        try {
            switch (key) {
                case "methodMaxLines" -> thresholds.setMethodMaxLines(Integer.parseInt(value));
                case "methodMaxComplexity" -> thresholds.setMethodMaxComplexity(Integer.parseInt(value));
                case "classMaxLines" -> thresholds.setClassMaxLines(Integer.parseInt(value));
                case "methodMaxParameters" -> thresholds.setMethodMaxParameters(Integer.parseInt(value));
                case "maxNestingDepth" -> thresholds.setMaxNestingDepth(Integer.parseInt(value));
                case "minFieldNameLength" -> thresholds.setMinFieldNameLength(Integer.parseInt(value));
                case "maxFindingsPerRulePerFile" ->
                        thresholds.setMaxFindingsPerRulePerFile(Integer.parseInt(value));
                case "requireJavadocForPublicMethods" ->
                        thresholds.setRequireJavadocForPublicMethods(Boolean.parseBoolean(value));
                case "classNamingPattern" -> thresholds.setClassNamingPattern(value);
                case "methodNamingPattern" -> thresholds.setMethodNamingPattern(value);
                case "constantNamingPattern" -> thresholds.setConstantNamingPattern(value);
                case "constantNameAllowlist" -> thresholds.setConstantNameAllowlist(splitList(value));
                case "shortNameAllowlist" -> thresholds.setShortNameAllowlist(splitList(value));
                case "magicNumberAllowlist" -> thresholds.setMagicNumberAllowlist(splitList(value));
                case "ignoredFilePatterns" -> thresholds.setIgnoredFilePatterns(splitList(value));
                default -> log.debug("未知的阈值配置键，已忽略: {}", key);
            }
        } catch (NumberFormatException e) {
            log.warn("阈值配置 {} 的值不是合法数字，已忽略: {}", key, value);
        }
    }

    private static List<String> splitList(String value) {
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    // ==================== 规则配置管理 ====================

    /**
     * 列出全部内置规则及其生效配置
     * <p>
     * 以内置规则为准遍历，再合并数据库中的覆盖项，保证新加规则立刻出现在
     * 管理页面上，而不需要先往配置表插一行。
     * @return 规则配置列表
     */
    public List<RuleConfig> listRules() {
        Map<String, RuleConfig> stored = new HashMap<>();
        try {
            for (RuleConfig ruleConfig : ruleConfigMapper.selectList(null)) {
                stored.put(ruleConfig.getRuleId(), ruleConfig);
            }
        } catch (RuntimeException e) {
            log.warn("读取规则配置失败: {}", e.toString());
        }
        List<RuleConfig> result = new ArrayList<>();
        for (Rule rule : DefaultRules.all()) {
            RuleConfig storedConfig = stored.get(rule.id());
            RuleConfig view = new RuleConfig(rule.id(), rule.name(), rule.type().name());
            view.setDescription(rule.description());
            if (storedConfig != null) {
                view.setEnabled(storedConfig.isEnabled());
                view.setSeverity(storedConfig.getSeverity());
                view.setConfidence(storedConfig.getConfidence());
            } else {
                view.setSeverity(rule.severity().name());
                view.setConfidence(rule.confidence());
            }
            result.add(view);
        }
        return result;
    }

    /**
     * 更新规则配置（启停 / 严重级 / 置信度）
     * @param update 含 ruleId 的配置
     * @return 更新后的配置
     */
    public RuleConfig updateRule(RuleConfig update) {
        LambdaQueryWrapper<RuleConfig> query = new LambdaQueryWrapper<>();
        query.eq(RuleConfig::getRuleId, update.getRuleId());
        RuleConfig existing = ruleConfigMapper.selectOne(query);
        if (existing == null) {
            update.setId(null);
            ruleConfigMapper.insert(update);
        } else {
            existing.setEnabled(update.isEnabled());
            existing.setSeverity(update.getSeverity());
            existing.setConfidence(update.getConfidence());
            ruleConfigMapper.updateById(existing);
            update = existing;
        }
        reload();
        return update;
    }

    // ==================== 阈值管理 ====================

    /** 列出全部阈值配置行 */
    public List<RuleThreshold> listThresholds() {
        LambdaQueryWrapper<RuleThreshold> query = new LambdaQueryWrapper<>();
        query.orderByAsc(RuleThreshold::getId);
        return thresholdMapper.selectList(query);
    }

    /**
     * 更新一条阈值
     * @param key 配置键
     * @param value 配置值
     * @return 是否更新成功
     */
    public boolean updateThreshold(String key, String value) {
        LambdaQueryWrapper<RuleThreshold> query = new LambdaQueryWrapper<>();
        query.eq(RuleThreshold::getConfigKey, key);
        RuleThreshold existing = thresholdMapper.selectOne(query);
        if (existing == null) {
            return false;
        }
        existing.setConfigValue(value);
        thresholdMapper.updateById(existing);
        reload();
        return true;
    }

    // ==================== 忽略列表 ====================

    /** 列出全部忽略项 */
    public List<IgnoreEntry> listIgnores() {
        LambdaQueryWrapper<IgnoreEntry> query = new LambdaQueryWrapper<>();
        query.orderByDesc(IgnoreEntry::getId);
        return ignoreEntryMapper.selectList(query);
    }

    /**
     * 新增忽略项
     * @param entry 忽略项
     * @return 已保存的忽略项
     */
    public IgnoreEntry addIgnore(IgnoreEntry entry) {
        entry.setId(null);
        entry.setCreateTime(new Date());
        ignoreEntryMapper.insert(entry);
        reload();
        return entry;
    }

    /**
     * 删除忽略项
     * @param id 忽略项 ID
     * @return 是否删除成功
     */
    public boolean deleteIgnore(Long id) {
        boolean removed = ignoreEntryMapper.deleteById(id) > 0;
        if (removed) {
            reload();
        }
        return removed;
    }
}
