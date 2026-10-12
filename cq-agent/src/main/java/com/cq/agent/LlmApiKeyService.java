package com.cq.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cq.agent.mapper.LlmApiKeyMapper;
import com.cq.common.model.LlmApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 大模型配置管理
 * <p>
 * 既是配置的增删查入口，也是运行时的配置来源（{@link LlmConfigProvider}）：
 * 启用项被缓存、写操作后失效，因此页面上的保存与启用会立即改变下一条建议的生成方式，
 * 无需重启服务。
 */
@Service
public class LlmApiKeyService implements LlmConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmApiKeyService.class);

    /** 表示「已查过、当前没有启用项」，避免每次调用都回查数据库 */
    private static final LlmSettings NONE = new LlmSettings(null, null, null, null, 0);

    private final LlmApiKeyMapper llmApiKeyMapper;
    private final int timeoutSeconds;

    private volatile LlmSettings cache;

    public LlmApiKeyService(LlmApiKeyMapper llmApiKeyMapper,
                            @Value("${cq.llm.timeout-seconds:60}") int timeoutSeconds) {
        this.llmApiKeyMapper = llmApiKeyMapper;
        this.timeoutSeconds = timeoutSeconds;
    }

    // ==================== LlmConfigProvider ====================

    @Override
    public LlmSettings current() {
        LlmSettings local = cache;
        if (local == null) {
            synchronized (this) {
                local = cache;
                if (local == null) {
                    local = load();
                    cache = local;
                }
            }
        }
        return local == NONE ? null : local;
    }

    /** 清空缓存，下次读取重新查库（配置被修改后调用） */
    public void reload() {
        cache = null;
    }

    /** 从数据库装配启用中的配置 */
    private LlmSettings load() {
        try {
            LambdaQueryWrapper<LlmApiKey> query = new LambdaQueryWrapper<>();
            query.eq(LlmApiKey::isActive, true).orderByDesc(LlmApiKey::getId).last("LIMIT 1");
            LlmApiKey row = llmApiKeyMapper.selectOne(query);
            if (row == null) {
                return NONE;
            }
            LlmSettings settings = new LlmSettings(row.getName(), row.getBaseUrl(),
                    row.getApiKey(), row.getModel(), timeoutSeconds);
            if (!settings.usable()) {
                log.warn("启用中的配置「{}」字段不完整，跳过并回退兜底实现", row.getName());
                return NONE;
            }
            return settings;
        } catch (RuntimeException e) {
            // 数据库不可用时退回上层兜底，而不是让建议生成整体失败
            log.warn("读取大模型配置失败，回退 application.yml / 离线实现: {}", e.toString());
            return NONE;
        }
    }

    // ==================== 管理 ====================

    /** 列出全部配置（密钥为掩码视图） */
    public List<LlmApiKeyView> list() {
        LambdaQueryWrapper<LlmApiKey> query = new LambdaQueryWrapper<>();
        query.orderByDesc(LlmApiKey::isActive).orderByDesc(LlmApiKey::getId);
        return llmApiKeyMapper.selectList(query).stream().map(LlmApiKeyView::from).toList();
    }

    /**
     * 新增配置
     * <p>
     * 表中尚无配置时自动启用：否则「保存了却仍是离线模式」会让人以为保存失败。
     */
    public LlmApiKeyView add(LlmApiKey entry) {
        normalize(entry);
        validate(entry);
        if (nameExists(entry.getName())) {
            throw new IllegalArgumentException("配置名称已存在：" + entry.getName());
        }
        boolean first = llmApiKeyMapper.selectCount(null) == 0;
        entry.setId(null);
        entry.setActive(first);
        entry.setCreateTime(new Date());
        try {
            llmApiKeyMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // 并发插入同名时由唯一索引兜底
            throw new IllegalArgumentException("配置名称已存在：" + entry.getName());
        }
        reload();
        return LlmApiKeyView.from(entry);
    }

    /** 删除配置；删掉的若正是启用项，运行时自动回退到 application.yml / 离线实现 */
    public boolean delete(Long id) {
        boolean removed = llmApiKeyMapper.deleteById(id) > 0;
        if (removed) {
            reload();
        }
        return removed;
    }

    /** 启用指定配置，同一时刻至多一条启用 */
    public LlmApiKeyView activate(Long id) {
        LlmApiKey target = llmApiKeyMapper.selectById(id);
        if (target == null) {
            throw new IllegalArgumentException("配置不存在");
        }
        // 先全部停用、再启用目标：中途失败只会「没有启用项」（安全回退），不会出现两条启用项
        LambdaUpdateWrapper<LlmApiKey> deactivate = new LambdaUpdateWrapper<>();
        deactivate.set(LlmApiKey::isActive, false).eq(LlmApiKey::isActive, true);
        llmApiKeyMapper.update(null, deactivate);

        LambdaUpdateWrapper<LlmApiKey> activate = new LambdaUpdateWrapper<>();
        activate.set(LlmApiKey::isActive, true).eq(LlmApiKey::getId, id);
        llmApiKeyMapper.update(null, activate);

        reload();
        target.setActive(true);
        return LlmApiKeyView.from(target);
    }

    private boolean nameExists(String name) {
        LambdaQueryWrapper<LlmApiKey> query = new LambdaQueryWrapper<>();
        query.eq(LlmApiKey::getName, name);
        return llmApiKeyMapper.selectCount(query) > 0;
    }

    private static void normalize(LlmApiKey entry) {
        entry.setName(trim(entry.getName()));
        entry.setBaseUrl(trim(entry.getBaseUrl()));
        entry.setApiKey(trim(entry.getApiKey()));
        entry.setModel(trim(entry.getModel()));
    }

    private static void validate(LlmApiKey entry) {
        requireText(entry.getName(), "配置名称不能为空");
        requireText(entry.getBaseUrl(), "接口地址不能为空");
        requireText(entry.getModel(), "模型名不能为空");
        requireText(entry.getApiKey(), "API Key 不能为空");
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
