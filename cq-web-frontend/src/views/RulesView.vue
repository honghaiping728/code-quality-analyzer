<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessageBox } from 'element-plus';

import TypeDot from '../components/TypeDot.vue';
import { ruleApi } from '../api';
import { formatConfidence } from '../constants';
import { toast } from '../composables/useToast';

const rules = ref([]);
const thresholdRows = ref([]);
const ignores = ref([]);
// 必须用 reactive：以 ref({...}) 声明却按 loading.rules = false 赋值，改的是 ref 对象自身
// 而不是它包裹的数据，模板读到的永远是初始值
const loading = reactive({ rules: true, thresholds: true, ignores: true });
const failed = reactive({ rules: '', thresholds: '', ignores: '' });
const saving = ref(false);
const adding = ref(false);

const ignoreForm = reactive({ ruleId: '', filePattern: '', reason: '' });

async function loadRules() {
    loading.rules = true;
    failed.rules = '';
    try {
        rules.value = await ruleApi.list();
    } catch (error) {
        failed.rules = error.message;
    } finally {
        loading.rules = false;
    }
}

async function loadThresholds() {
    loading.thresholds = true;
    failed.thresholds = '';
    try {
        thresholdRows.value = await ruleApi.thresholds();
    } catch (error) {
        failed.thresholds = error.message;
    } finally {
        loading.thresholds = false;
    }
}

async function loadIgnores() {
    loading.ignores = true;
    failed.ignores = '';
    try {
        ignores.value = await ruleApi.ignores();
    } catch (error) {
        failed.ignores = error.message;
    } finally {
        loading.ignores = false;
    }
}

async function toggleRule(row) {
    const next = !row.enabled;
    try {
        await ruleApi.setEnabled(row.ruleId, next);
        await loadRules();
        toast(next ? `已启用 ${row.ruleId}` : `已停用 ${row.ruleId}`, 'success');
    } catch (error) {
        toast(`更新失败：${error.message}`, 'error');
    }
}

/** 阈值逐行 PATCH：后端没有批量接口，保持与旧控制台一致的行为 */
async function saveThresholds() {
    saving.value = true;
    try {
        for (const row of thresholdRows.value) {
            await ruleApi.setThreshold(row.configKey, row.configValue);
        }
        toast('阈值已保存，下次扫描生效', 'success');
    } catch (error) {
        toast(`保存失败：${error.message}`, 'error');
    } finally {
        saving.value = false;
    }
}

async function addIgnore() {
    adding.value = true;
    try {
        await ruleApi.addIgnore({
            ruleId: ignoreForm.ruleId.trim() || null,
            filePattern: ignoreForm.filePattern.trim(),
            reason: ignoreForm.reason.trim(),
        });
        ignoreForm.ruleId = '';
        ignoreForm.filePattern = '';
        ignoreForm.reason = '';
        await loadIgnores();
    } catch (error) {
        toast(`添加失败：${error.message}`, 'error');
    } finally {
        adding.value = false;
    }
}

async function removeIgnore(row) {
    try {
        await ElMessageBox.confirm(
            `确认删除忽略项「${row.filePattern}」？删除后该路径下被忽略的问题会重新进入结果。`,
            '删除忽略项',
            { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
        );
    } catch (e) {
        return;   // 用户取消
    }
    try {
        await ruleApi.deleteIgnore(row.id);
        await loadIgnores();
    } catch (error) {
        toast(`删除失败：${error.message}`, 'error');
    }
}

onMounted(() => {
    loadRules();
    loadThresholds();
    loadIgnores();
});
</script>

<template>
    <div class="view-stack">
        <section class="panel">
            <h2>规则启停与阈值 <span class="count">共 {{ rules.length }} 条</span></h2>
            <p class="hint">关闭某条规则后，后续扫描不再执行它；调整后立即生效。</p>

            <el-table v-if="rules.length" :data="rules" data-test="rule-table">
                <el-table-column label="状态" width="92">
                    <template #default="{ row }">
                        <el-switch
                            :model-value="row.enabled"
                            data-test="rule-toggle"
                            @change="toggleRule(row)"
                        />
                    </template>
                </el-table-column>
                <el-table-column label="规则 ID" width="230">
                    <template #default="{ row }"><span class="mono">{{ row.ruleId }}</span></template>
                </el-table-column>
                <el-table-column prop="ruleName" label="名称" min-width="170" show-overflow-tooltip />
                <el-table-column label="维度" width="100">
                    <template #default="{ row }"><TypeDot :type="row.category" /></template>
                </el-table-column>
                <el-table-column label="严重级" width="100">
                    <template #default="{ row }">
                        <span v-if="row.severity" class="badge">{{ row.severity }}</span>
                        <span v-else class="hint">默认</span>
                    </template>
                </el-table-column>
                <el-table-column label="置信度" width="90">
                    <template #default="{ row }">
                        <span class="mono">{{ row.confidence == null ? '—' : formatConfidence(row.confidence) }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="说明" min-width="240" show-overflow-tooltip>
                    <template #default="{ row }"><span class="hint">{{ row.description }}</span></template>
                </el-table-column>
            </el-table>
            <p v-else-if="loading.rules" class="empty">加载中…</p>
            <p v-else-if="failed.rules" class="empty">加载失败：{{ failed.rules }}</p>
        </section>

        <section class="panel">
            <h2>阈值参数</h2>
            <p class="hint">这些阈值决定「方法过长」「嵌套过深」等规则的判定边界。</p>

            <el-table v-if="thresholdRows.length" :data="thresholdRows" data-test="threshold-table">
                <el-table-column label="配置键" width="260">
                    <template #default="{ row }"><span class="mono">{{ row.configKey }}</span></template>
                </el-table-column>
                <el-table-column label="值" width="280">
                    <template #default="{ row }">
                        <el-input v-model="row.configValue" size="small" class="threshold-input" />
                    </template>
                </el-table-column>
                <el-table-column label="说明" min-width="280">
                    <template #default="{ row }"><span class="hint">{{ row.description }}</span></template>
                </el-table-column>
            </el-table>
            <p v-else-if="loading.thresholds" class="empty">加载中…</p>
            <p v-else-if="failed.thresholds" class="empty">加载失败：{{ failed.thresholds }}</p>

            <div class="toolbar">
                <el-button type="primary" :loading="saving" data-test="save-thresholds" @click="saveThresholds">
                    保存阈值
                </el-button>
            </div>
        </section>

        <section class="panel">
            <h2>忽略列表</h2>
            <p class="hint">按路径 glob 忽略整个文件，或按规则忽略特定行。被忽略的问题不会进入结果。</p>

            <el-table v-if="ignores.length" :data="ignores" data-test="ignore-table">
                <el-table-column label="规则" width="230">
                    <template #default="{ row }">
                        <span class="mono">{{ row.ruleId || '（全部规则）' }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="路径" min-width="240" show-overflow-tooltip>
                    <template #default="{ row }"><span class="mono">{{ row.filePattern }}</span></template>
                </el-table-column>
                <el-table-column label="原因" min-width="180" show-overflow-tooltip>
                    <template #default="{ row }"><span class="hint">{{ row.reason }}</span></template>
                </el-table-column>
                <el-table-column width="90">
                    <template #default="{ row }">
                        <el-button size="small" data-test="ignore-delete" @click="removeIgnore(row)">删除</el-button>
                    </template>
                </el-table-column>
            </el-table>
            <p v-else-if="loading.ignores" class="empty">加载中…</p>
            <p v-else-if="failed.ignores" class="empty">加载失败：{{ failed.ignores }}</p>
            <p v-else class="empty">暂无忽略项</p>

            <form class="scan-form" @submit.prevent="addIgnore">
                <el-input v-model="ignoreForm.ruleId" placeholder="规则 ID（留空则忽略该路径下所有规则）" data-test="ignore-rule" />
                <el-input v-model="ignoreForm.filePattern" placeholder="路径 glob，如 **/test/**" data-test="ignore-pattern" />
                <el-input v-model="ignoreForm.reason" placeholder="忽略原因" data-test="ignore-reason" />
                <el-button native-type="submit" :loading="adding" data-test="ignore-add">添加</el-button>
            </form>
        </section>
    </div>
</template>

<style scoped>
.scan-form {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
    margin-top: var(--sp-4);
}

.scan-form :deep(.el-input) { flex: 1 1 200px; }

.threshold-input { max-width: 240px; }

.toolbar { margin-top: var(--sp-4); }
</style>
