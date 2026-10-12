<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessageBox } from 'element-plus';

import { llmApi } from '../api';
import { toast } from '../composables/useToast';
import { useStatus } from '../composables/useStatus';

const { load: reloadStatus } = useStatus();

const keys = ref([]);
const loading = ref(true);
const failed = ref('');
const saving = ref(false);
const acting = ref('');

const form = reactive({ name: '', baseUrl: '', model: '', apiKey: '' });

async function load() {
    loading.value = true;
    failed.value = '';
    try {
        keys.value = await llmApi.list();
    } catch (error) {
        keys.value = [];
        failed.value = error.message;
    } finally {
        loading.value = false;
    }
}

async function add() {
    saving.value = true;
    try {
        const saved = await llmApi.add({
            name: form.name.trim(),
            baseUrl: form.baseUrl.trim(),
            model: form.model.trim(),
            apiKey: form.apiKey.trim(),
        });
        form.name = '';
        form.baseUrl = '';
        form.model = '';
        form.apiKey = '';
        await load();
        await reloadStatus();
        toast(saved.active ? `「${saved.name}」已保存并启用` : `「${saved.name}」已保存`, 'success');
    } catch (error) {
        toast(`保存失败：${error.message}`, 'error');
    } finally {
        saving.value = false;
    }
}

async function activate(row) {
    acting.value = `activate-${row.id}`;
    try {
        await llmApi.activate(row.id);
        await load();
        await reloadStatus();
        toast('已启用，下一次调用即生效', 'success');
    } catch (error) {
        toast(`启用失败：${error.message}`, 'error');
    } finally {
        acting.value = '';
    }
}

async function remove(row) {
    try {
        await ElMessageBox.confirm(
            `确认删除配置「${row.name}」？若它正在启用，系统会回退到 application.yml 或离线启发式实现。`,
            '删除配置',
            { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
        );
    } catch (e) {
        return;   // 用户取消
    }
    acting.value = `delete-${row.id}`;
    try {
        await llmApi.remove(row.id);
        await load();
        await reloadStatus();
    } catch (error) {
        toast(`删除失败：${error.message}`, 'error');
    } finally {
        acting.value = '';
    }
}

const activeKey = computed(() => keys.value.find((item) => item.active) || null);

onMounted(load);
</script>

<template>
    <div class="view-stack">
        <section class="panel">
            <h2>大模型配置 <span class="count">共 {{ keys.length }} 条</span></h2>
            <p class="hint">
                接入任意 OpenAI 兼容服务（DeepSeek / 通义 / Kimi / 智谱等）：填写名称、接口地址、模型与密钥后保存。
                启用中的配置立即生效、无需重启；没有启用项时回退到 application.yml，仍无密钥则使用离线启发式实现。
                密钥明文存于本地数据库，接口只返回掩码。
            </p>

            <div v-if="activeKey" class="active-banner" data-test="llm-active-banner">
                当前生效：<strong>{{ activeKey.name }}</strong>
                <span class="mono">· {{ activeKey.model }}</span>
                <span class="mono">· {{ activeKey.baseUrl }}</span>
            </div>
            <div v-else class="active-banner is-idle" data-test="llm-active-banner">
                当前没有启用中的数据库配置，运行时使用 application.yml 或离线启发式实现。
            </div>

            <el-table v-if="keys.length" :data="keys" data-test="llm-key-table">
                <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
                <el-table-column label="接口地址" min-width="240" show-overflow-tooltip>
                    <template #default="{ row }"><span class="mono">{{ row.baseUrl }}</span></template>
                </el-table-column>
                <el-table-column label="模型" width="170" show-overflow-tooltip>
                    <template #default="{ row }"><span class="mono">{{ row.model }}</span></template>
                </el-table-column>
                <el-table-column label="密钥" width="130">
                    <template #default="{ row }"><span class="mono key-preview">{{ row.keyPreview }}</span></template>
                </el-table-column>
                <el-table-column label="状态" width="96">
                    <template #default="{ row }">
                        <span v-if="row.active" class="badge badge-ok" data-test="llm-active-badge">启用中</span>
                    </template>
                </el-table-column>
                <el-table-column width="160">
                    <template #default="{ row }">
                        <el-button
                            v-if="!row.active"
                            size="small"
                            :loading="acting === `activate-${row.id}`"
                            :data-test="`llm-activate-${row.id}`"
                            @click="activate(row)"
                        >
                            启用
                        </el-button>
                        <el-button
                            size="small"
                            :loading="acting === `delete-${row.id}`"
                            :data-test="`llm-delete-${row.id}`"
                            @click="remove(row)"
                        >
                            删除
                        </el-button>
                    </template>
                </el-table-column>
            </el-table>
            <p v-else-if="loading" class="empty">加载中…</p>
            <p v-else-if="failed" class="empty">加载失败：{{ failed }}</p>
            <p v-else class="empty">暂无配置，填写下方表单即可接入真实模型</p>

            <form class="scan-form" @submit.prevent="add">
                <el-input v-model="form.name" placeholder="配置名称，如 DeepSeek 生产" data-test="llm-name" />
                <el-input v-model="form.baseUrl" placeholder="接口地址，如 https://api.deepseek.com/v1" data-test="llm-base-url" />
                <el-input v-model="form.model" placeholder="模型名，如 deepseek-chat" data-test="llm-model" />
                <el-input
                    v-model="form.apiKey"
                    type="password"
                    show-password
                    autocomplete="off"
                    placeholder="API Key，如 sk-xxxx"
                    data-test="llm-secret"
                />
                <el-button type="primary" native-type="submit" :loading="saving" data-test="llm-save">保存</el-button>
            </form>
        </section>
    </div>
</template>

<style scoped>
.active-banner {
    margin: var(--sp-4) 0;
    padding: 9px 13px;
    font-size: 12.5px;
    color: var(--text-2);
    background: var(--accent-soft);
    border-left: 3px solid var(--accent);
    border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

.active-banner.is-idle {
    background: var(--surface-2);
    border-left-color: var(--border-strong);
    color: var(--text-muted);
}

.active-banner strong { color: var(--text); }

.key-preview { color: var(--text-muted); }

.scan-form {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
    margin-top: var(--sp-5);
}

.scan-form :deep(.el-input) { flex: 1 1 200px; }
</style>
