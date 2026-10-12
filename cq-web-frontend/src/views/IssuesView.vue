<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import MetricStrip from '../components/MetricStrip.vue';
import SeverityBadge from '../components/SeverityBadge.vue';
import TypeDot from '../components/TypeDot.vue';
import { issueApi, scanApi } from '../api';
import { PAGE_SIZES, SEVERITY_ORDER, TYPE_LABELS, TYPE_ORDER, formatConfidence } from '../constants';
import { toast } from '../composables/useToast';

const route = useRoute();
const router = useRouter();

/**
 * 筛选条件全部放在 URL 查询串里：从概览跳转过来的任务筛选、刷新、分享链接、
 * 浏览器后退都能还原现场——旧控制台把状态放在内存里，刷新即丢。
 */
const filters = reactive({
    keyword: String(route.query.keyword ?? ''),
    task: String(route.query.task ?? ''),
    type: String(route.query.type ?? ''),
    severity: String(route.query.severity ?? ''),
});

const keywordInput = ref(filters.keyword);
const pageSize = ref(Number(route.query.size) || 20);
const page = ref(1);

const allItems = ref([]);
const loading = ref(false);
const failed = ref('');
const taskOptions = ref([]);

/** 后端没有关键词参数：一次取回后在客户端过滤（上限 1000 条） */
const items = computed(() => {
    const keyword = filters.keyword.trim().toLowerCase();
    if (!keyword) {
        return allItems.value;
    }
    return allItems.value.filter((issue) =>
        [issue.filePath, issue.message, issue.ruleId]
            .some((field) => String(field ?? '').toLowerCase().includes(keyword)));
});

const metrics = computed(() => {
    const list = items.value;
    const countBy = (key, value) => list.filter((issue) => issue[key] === value).length;
    return [
        { label: '问题总数', value: list.length, accent: true },
        ...TYPE_ORDER.map((type) => ({ label: TYPE_LABELS[type], value: countBy('type', type) })),
        ...SEVERITY_ORDER.map((severity) => ({ label: severity, value: countBy('severity', severity) })),
    ];
});

/** 分页切片：页码被夹在有效范围内，数据变少时不会停在空白页 */
const pageCount = computed(() => Math.max(1, Math.ceil(items.value.length / pageSize.value)));
const pagedItems = computed(() => {
    const current = Math.min(Math.max(1, page.value), pageCount.value);
    const start = (current - 1) * pageSize.value;
    return items.value.slice(start, start + pageSize.value);
});

async function load() {
    loading.value = true;
    failed.value = '';
    try {
        allItems.value = await issueApi.list({
            taskId: filters.task,
            type: filters.type,
            severity: filters.severity,
        });
    } catch (error) {
        allItems.value = [];
        failed.value = error.message;
        toast(`加载失败：${error.message}`, 'error');
    } finally {
        loading.value = false;
    }
}

async function loadTaskOptions() {
    try {
        taskOptions.value = await scanApi.tasks(50);
    } catch (error) {
        taskOptions.value = [];
    }
}

/** 任务不在最近 50 条里（例如从旧链接进来）时补一个选项，避免筛选值静默丢失 */
const taskSelectOptions = computed(() => {
    const options = taskOptions.value.map((task) => ({
        value: String(task.id),
        label: `#${task.id} ${task.targetPath || ''}（${task.issueCount ?? 0} 个问题）`,
    }));
    if (filters.task && !options.some((option) => option.value === filters.task)) {
        options.unshift({ value: filters.task, label: `#${filters.task}（当前筛选的任务）` });
    }
    return options;
});

/** 把本地筛选写进 URL —— 由 watch(route.query) 统一触发加载 */
function applyFilters(resetKeyword = false) {
    if (resetKeyword) {
        filters.keyword = keywordInput.value;
    }
    page.value = 1;
    router.push({
        path: '/issues',
        query: {
            ...(filters.task ? { task: filters.task } : {}),
            ...(filters.type ? { type: filters.type } : {}),
            ...(filters.severity ? { severity: filters.severity } : {}),
            ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
            ...(pageSize.value !== 20 ? { size: String(pageSize.value) } : {}),
        },
    });
}

/** 关键词只在"应用"或回车时生效，避免每敲一个字都改 URL */
function submitKeyword() {
    applyFilters(true);
}

watch(
    () => route.query,
    (next) => {
        filters.task = String(next.task ?? '');
        filters.type = String(next.type ?? '');
        filters.severity = String(next.severity ?? '');
        filters.keyword = String(next.keyword ?? '');
        keywordInput.value = filters.keyword;
        page.value = 1;
        load();
    },
);

watch(pageSize, () => {
    page.value = 1;
});

function openDetail(row) {
    router.push(`/issues/${row.id}`);
}

onMounted(() => {
    loadTaskOptions();
    load();
});
</script>

<template>
    <div class="view-stack">
        <section class="panel">
            <div class="filters">
                <el-input
                    v-model="keywordInput"
                    class="filter-keyword"
                    placeholder="文件 / 描述 / 规则"
                    clearable
                    data-test="filter-keyword"
                    @keydown.enter.prevent="submitKeyword"
                    @clear="submitKeyword"
                />
                <el-select v-model="filters.task" class="filter-select" placeholder="全部任务" data-test="filter-task" @change="applyFilters()">
                    <el-option label="全部" value="" />
                    <el-option
                        v-for="option in taskSelectOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                </el-select>
                <el-select v-model="filters.type" class="filter-select" placeholder="全部类型" data-test="filter-type" @change="applyFilters()">
                    <el-option label="全部" value="" />
                    <el-option v-for="type in TYPE_ORDER" :key="type" :label="TYPE_LABELS[type]" :value="type" />
                </el-select>
                <el-select v-model="filters.severity" class="filter-select" placeholder="全部严重级" data-test="filter-severity" @change="applyFilters()">
                    <el-option label="全部" value="" />
                    <el-option v-for="severity in SEVERITY_ORDER" :key="severity" :label="severity" :value="severity" />
                </el-select>
                <el-button type="primary" data-test="apply-filter" @click="submitKeyword">应用</el-button>
            </div>
            <MetricStrip :items="metrics" />
        </section>

        <section class="panel">
            <div class="panel-head">
                <h2>问题清单 <span class="count" data-test="issue-count">共 {{ items.length }} 条</span></h2>
            </div>

            <el-table
                v-if="pagedItems.length"
                :data="pagedItems"
                class="clickable-rows"
                data-test="issue-table"
                @row-click="openDetail"
            >
                <el-table-column label="严重级" width="106">
                    <template #default="{ row }"><SeverityBadge :severity="row.severity" /></template>
                </el-table-column>
                <el-table-column label="类型" width="100">
                    <template #default="{ row }"><TypeDot :type="row.type" /></template>
                </el-table-column>
                <el-table-column label="文件" width="290" show-overflow-tooltip>
                    <template #default="{ row }"><span class="mono">{{ row.filePath }}</span></template>
                </el-table-column>
                <el-table-column label="行" width="72">
                    <template #default="{ row }"><span class="mono">{{ row.line }}</span></template>
                </el-table-column>
                <el-table-column label="规则" width="210" show-overflow-tooltip>
                    <template #default="{ row }"><span class="mono">{{ row.ruleId }}</span></template>
                </el-table-column>
                <el-table-column label="描述" min-width="260" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.message }}</template>
                </el-table-column>
                <el-table-column label="置信度" width="88">
                    <template #default="{ row }"><span class="mono">{{ formatConfidence(row.confidence) }}</span></template>
                </el-table-column>
            </el-table>

            <p v-else-if="loading" class="empty">加载中…</p>
            <p v-else-if="failed" class="empty">加载失败：{{ failed }}</p>
            <p v-else-if="!allItems.length" class="empty">暂无数据，请先发起扫描</p>
            <p v-else class="empty">没有符合条件的问题</p>

            <el-pagination
                v-if="items.length > pageSize"
                v-model:current-page="page"
                :page-size="pageSize"
                :total="items.length"
                :page-sizes="PAGE_SIZES"
                layout="total, sizes, prev, pager, next"
                data-test="issue-pagination"
                @size-change="(size) => { pageSize = size; }"
            />
        </section>
    </div>
</template>

<style scoped>
.filters {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
    margin-bottom: var(--sp-4);
}

.filter-keyword { flex: 1 1 240px; min-width: 200px; }
.filter-select { width: 180px; flex: 0 1 auto; }

.clickable-rows :deep(.el-table__row) {
    cursor: pointer;
}
</style>
