<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { UploadFilled } from '@element-plus/icons-vue';

import MetricStrip from '../components/MetricStrip.vue';
import TypeDot from '../components/TypeDot.vue';
import { scanApi, systemApi } from '../api';
import { SUPPORTED_EXTENSIONS, TYPE_LABELS, TYPE_ORDER, formatTime } from '../constants';
import { toast } from '../composables/useToast';
import { useStatus } from '../composables/useStatus';

const router = useRouter();
const { status, load: loadStatus } = useStatus();

/* ---------------- 四种代码来源 ---------------- */

const SOURCES = [
    { key: 'path', label: '服务器目录' },
    { key: 'upload', label: '上传文件' },
    { key: 'snippet', label: '粘贴代码' },
    { key: 'repo', label: '仓库地址' },
];

const source = ref('path');

const form = reactive({
    path: '',
    mode: 'FULL',
    snippetName: '',
    snippetSource: '',
    repoUrl: '',
    repoBranch: '',
    repoMode: 'FULL',
    repoBaseCommit: '',
    repoHeadCommit: '',
});

const files = ref([]);
const busy = ref(false);
const feedback = reactive({ text: '', error: false, visible: false });
const progress = reactive({ visible: false, text: '正在扫描…' });

function flash(text, error = false) {
    feedback.text = text;
    feedback.error = error;
    feedback.visible = true;
}

function pickSource(next) {
    source.value = next;
    feedback.visible = false;
}

const buttonLabel = computed(() => {
    if (!busy.value) {
        return { path: '开始扫描', upload: '上传并审查', snippet: '审查这段代码', repo: '在线扫描' }[source.value];
    }
    return { path: '扫描中…', upload: '上传中…', snippet: '审查中…', repo: '扫描中…' }[source.value];
});

function onFileChange(event) {
    files.value = Array.from(event.target.files ?? []);
}

/**
 * 轮询任务直到结束
 * <p>
 * 700ms 一次、最多 120 次（约 84 秒）；超时不报错，提示到任务列表查看——
 * 扫描本身仍在后端继续，不该让用户以为失败了。
 */
async function pollTask(taskId) {
    const startedAt = Date.now();
    progress.visible = true;
    progress.text = '正在扫描…';
    for (let attempt = 0; attempt < 120; attempt += 1) {
        const task = await scanApi.task(taskId);
        if (task.status === 'SUCCESS') {
            flash(`扫描完成：${task.fileCount} 个文件，发现 ${task.issueCount} 个问题，耗时 ${task.durationMs} ms`);
            // 报告生成失败不影响扫描结果展示，静默忽略
            await scanApi.buildReport(taskId).catch(() => null);
            await loadRecentTasks();
            await loadStatus();
            progress.visible = false;
            router.push({ path: '/issues', query: { task: String(taskId) } });
            return;
        }
        if (task.status === 'FAILED') {
            flash(`扫描失败：${task.errorMessage || '未知原因'}`, true);
            progress.visible = false;
            return;
        }
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        progress.text = `正在扫描… 已用时 ${seconds} 秒 · 已扫描 ${task.fileCount ?? 0} 个文件`
            + ` · 暂发现 ${task.issueCount ?? 0} 个问题`;
        await new Promise((resolve) => setTimeout(resolve, 700));
    }
    flash('扫描仍在进行，请稍后在任务列表中查看', true);
    progress.visible = false;
}

/** 四个来源共用的提交外壳：置忙 → 提交 → 轮询 → 无论如何都解除置忙 */
async function submit(taskFactory) {
    busy.value = true;
    try {
        const taskId = await taskFactory();
        await pollTask(taskId);
    } catch (error) {
        flash(error.message, true);
        progress.visible = false;
    } finally {
        busy.value = false;
    }
}

function submitPath() {
    if (!form.path.trim()) {
        flash('请输入扫描路径', true);
        return;
    }
    const target = form.path.trim();
    flash(`已提交扫描任务：${target}，正在解析与检查…`);
    submit(async () => (await scanApi.startTask({ path: target, mode: form.mode })).id);
}

function submitUpload() {
    if (!files.value.length) {
        flash('请先选择要审查的文件', true);
        return;
    }
    const chosen = [...files.value];
    flash(`已上传 ${chosen.length} 个文件，正在解析与检查…`);
    submit(async () => {
        const body = await scanApi.upload(chosen);
        // 后端会说明跳过了哪些不支持的文件，这条信息不该被吞掉
        if (body.message && body.message !== 'success') {
            flash(body.message);
        }
        files.value = [];
        return body.data.id;
    });
}

function submitSnippet() {
    if (!form.snippetSource.trim()) {
        flash('请先粘贴要审查的代码', true);
        return;
    }
    submit(async () => (await scanApi.snippet({
        fileName: form.snippetName.trim(),
        source: form.snippetSource,
    })).id);
}

function submitRepo() {
    if (!form.repoUrl.trim()) {
        flash('请输入 GitHub 仓库地址', true);
        return;
    }
    const url = form.repoUrl.trim();
    flash(`已提交仓库扫描：${url}，正在在线拉取源码并分析…`);
    submit(async () => (await scanApi.repo({
        url,
        branch: form.repoBranch.trim(),
        mode: form.repoMode,
        baseCommit: form.repoBaseCommit.trim(),
        headCommit: form.repoHeadCommit.trim(),
    })).id);
}

/* ---------------- 规则库指标 ---------------- */

const ruleMetrics = computed(() => {
    const byCategory = status.value?.rulesByCategory;
    if (!byCategory) {
        return [{ label: '规则总数', value: '—' }];
    }
    return [
        { label: '规则总数', value: status.value.ruleCount, accent: true },
        ...TYPE_ORDER.map((type) => ({ label: TYPE_LABELS[type], value: byCategory[type] ?? 0 })),
    ];
});

/* ---------------- 最近任务 ---------------- */

const tasks = ref([]);
const tasksLoaded = ref(false);

async function loadRecentTasks() {
    try {
        tasks.value = await scanApi.tasks(8);
    } catch (error) {
        tasks.value = [];
    } finally {
        tasksLoaded.value = true;
    }
}

function openTask(task) {
    router.push({ path: '/issues', query: { task: String(task.id) } });
}

onMounted(() => {
    loadRecentTasks();
    loadStatus();
});
</script>

<template>
    <div class="view-stack">
        <div class="overview-top">
            <!-- ============ 发起审查 ============ -->
            <section class="panel">
                <h2>发起审查</h2>

                <div class="source-tabs" data-test="source-tabs">
                    <button
                        v-for="item in SOURCES"
                        :key="item.key"
                        type="button"
                        class="source-tab"
                        :class="{ 'is-active': source === item.key }"
                        :data-source="item.key"
                        @click="pickSource(item.key)"
                    >
                        {{ item.label }}
                    </button>
                </div>

                <!-- 服务器目录 -->
                <div class="source-pane" :class="{ 'is-active': source === 'path' }" data-test="pane-path">
                    <p class="hint">输入服务器上可访问的目录或单个文件路径。默认跳过 target、generated、node_modules 等目录。</p>
                    <form class="scan-form" @submit.prevent="submitPath">
                        <el-input
                            v-model="form.path"
                            placeholder="/root/projects/code-quality-analyzer/cq-parser/src"
                            autocomplete="off"
                            data-test="scan-path"
                        />
                        <el-select v-model="form.mode" class="mode-select" data-test="scan-mode">
                            <el-option label="全量扫描" value="FULL" />
                            <el-option label="增量扫描" value="INCREMENTAL" />
                        </el-select>
                        <el-button type="primary" native-type="submit" :loading="busy" data-test="scan-button">
                            {{ buttonLabel }}
                        </el-button>
                    </form>
                </div>

                <!-- 上传文件 -->
                <div class="source-pane" :class="{ 'is-active': source === 'upload' }" data-test="pane-upload">
                    <p class="hint">
                        支持 Java、各类配置与脚本、前端源码等文本文件；文件只在内存中解析、不落盘，
                        文件名不参与任何文件系统操作，不支持的扩展名会被拒绝并明确列出。
                        一次最多 50 个、单文件不超过 2 MB。
                    </p>
                    <form class="scan-form scan-form-stack" @submit.prevent="submitUpload">
                        <label class="dropzone">
                            <el-icon class="dropzone-icon"><UploadFilled /></el-icon>
                            <span class="dropzone-title">点击选择或拖拽文件到这里</span>
                            <span class="dropzone-hint">
                                {{ files.length ? `已选择 ${files.length} 个文件` : '支持多选（拖拽不限类型，由服务端判定）' }}
                            </span>
                            <input
                                type="file"
                                :accept="SUPPORTED_EXTENSIONS.join(',')"
                                multiple
                                data-test="upload-files"
                                @change="onFileChange"
                            >
                        </label>
                        <el-button type="primary" native-type="submit" :loading="busy" data-test="upload-button">
                            {{ buttonLabel }}
                        </el-button>
                    </form>
                </div>

                <!-- 粘贴代码 -->
                <div class="source-pane" :class="{ 'is-active': source === 'snippet' }" data-test="pane-snippet">
                    <p class="hint">
                        粘贴任意一段代码或配置。语言按文件名的扩展名识别，不填则按 Java 处理。
                    </p>
                    <form class="scan-form scan-form-stack" @submit.prevent="submitSnippet">
                        <el-input
                            v-model="form.snippetName"
                            placeholder="文件名（可选，按扩展名识别语言），如 Demo.java / config.yml"
                            data-test="snippet-name"
                        />
                        <el-input
                            v-model="form.snippetSource"
                            type="textarea"
                            :rows="12"
                            spellcheck="false"
                            placeholder="在此粘贴要审查的代码或配置…"
                            data-test="snippet-source"
                        />
                        <el-button type="primary" native-type="submit" :loading="busy" data-test="snippet-button">
                            {{ buttonLabel }}
                        </el-button>
                    </form>
                </div>

                <!-- 仓库地址 -->
                <div class="source-pane" :class="{ 'is-active': source === 'repo' }" data-test="pane-repo">
                    <p class="hint">
                        仅支持公开的 GitHub 仓库，在线直取、不克隆不落盘。增量扫描只分析两次提交之间变更的文件。
                    </p>
                    <form class="scan-form scan-form-stack" @submit.prevent="submitRepo">
                        <el-input
                            v-model="form.repoUrl"
                            placeholder="https://github.com/owner/repo.git，也可直接填 owner/repo"
                            autocomplete="off"
                            data-test="repo-url"
                        />
                        <el-input v-model="form.repoBranch" placeholder="分支（可选，留空使用仓库默认分支）" data-test="repo-branch" />
                        <el-select v-model="form.repoMode" data-test="repo-mode">
                            <el-option label="全量扫描" value="FULL" />
                            <el-option label="增量扫描" value="INCREMENTAL" />
                        </el-select>
                        <div v-if="form.repoMode === 'INCREMENTAL'" class="commit-row" data-test="repo-commit-row">
                            <el-input
                                v-model="form.repoBaseCommit"
                                placeholder="基线提交（留空：上次扫描的提交，首次为父提交）"
                            />
                            <el-input v-model="form.repoHeadCommit" placeholder="目标提交（留空：默认分支最新提交）" />
                        </div>
                        <el-button type="primary" native-type="submit" :loading="busy" data-test="repo-button">
                            {{ buttonLabel }}
                        </el-button>
                    </form>
                </div>

                <!-- 反馈与进度（四个来源共用） -->
                <div v-show="feedback.visible" class="scan-feedback" :class="{ 'is-error': feedback.error }" data-test="scan-feedback">
                    {{ feedback.text }}
                </div>
                <div v-show="progress.visible" class="scan-progress" data-test="scan-progress">
                    <span class="spinner" aria-hidden="true"></span>
                    <span data-test="scan-progress-text">{{ progress.text }}</span>
                </div>
            </section>

            <!-- ============ 规则库 ============ -->
            <section class="panel">
                <h2>规则库 <span class="count">四维确定性检查</span></h2>
                <div class="rule-type-list">
                    <TypeDot v-for="type in TYPE_ORDER" :key="type" :type="type" />
                </div>
                <MetricStrip :items="ruleMetrics" />
            </section>
        </div>

        <!-- ============ 最近扫描任务 ============ -->
        <section class="panel">
            <h2>最近扫描任务</h2>
            <el-table
                v-if="tasks.length"
                :data="tasks"
                class="clickable-rows"
                data-test="recent-tasks"
                @row-click="openTask"
            >
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="扫描路径" min-width="260" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.targetPath || '—' }}</template>
                </el-table-column>
                <el-table-column label="模式" width="110">
                    <template #default="{ row }"><span class="badge">{{ row.mode }}</span></template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                    <template #default="{ row }"><span class="badge">{{ row.status }}</span></template>
                </el-table-column>
                <el-table-column label="文件" width="80">
                    <template #default="{ row }">{{ row.fileCount ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="问题" width="80">
                    <template #default="{ row }">{{ row.issueCount ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="耗时" width="100">
                    <template #default="{ row }">{{ row.durationMs == null ? '—' : `${row.durationMs} ms` }}</template>
                </el-table-column>
                <el-table-column label="创建时间" width="170">
                    <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
                </el-table-column>
            </el-table>
            <p v-else-if="tasksLoaded" class="empty">暂无扫描记录</p>
            <p v-else class="empty">加载中…</p>
        </section>
    </div>
</template>

<style scoped>
.overview-top {
    display: grid;
    grid-template-columns: minmax(0, 1.65fr) minmax(300px, 1fr);
    gap: var(--sp-5);
    align-items: start;
}

@media (max-width: 1100px) {
    .overview-top { grid-template-columns: 1fr; }
}

/* ---------------- 来源页签：分段控件 ---------------- */

.source-tabs {
    display: flex;
    gap: 2px;
    margin: var(--sp-4) 0 0;
    padding: 3px;
    background: var(--surface-2);
    border-radius: var(--radius-sm);
    overflow-x: auto;
    scrollbar-width: none;
}

.source-tabs::-webkit-scrollbar { display: none; }

.source-tab {
    flex: 1 1 auto;
    padding: 7px 12px;
    font-size: 13px;
    color: var(--text-2);
    background: transparent;
    border: none;
    border-radius: 5px;
    cursor: pointer;
    white-space: nowrap;
    transition: color .15s ease, background-color .15s ease, box-shadow .15s ease;
}

.source-tab:hover { color: var(--text); }

.source-tab.is-active {
    color: var(--text);
    font-weight: 600;
    background: var(--surface);
    box-shadow: var(--shadow-1);
}

/* ---------------- 各来源面板 ---------------- */

.source-pane { display: none; }

.source-pane.is-active { display: block; }

.scan-form {
    display: flex;
    gap: var(--sp-2);
    align-items: center;
    margin-top: var(--sp-3);
}

.scan-form-stack {
    flex-direction: column;
    align-items: stretch;
}

.scan-form-stack > .el-button { align-self: flex-start; }

.mode-select { width: 140px; flex: none; }

.commit-row {
    display: flex;
    gap: var(--sp-2);
}

/* ---------------- 上传拖放区 ---------------- */

.dropzone {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    padding: var(--sp-6);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--surface-2);
    cursor: pointer;
    text-align: center;
    transition: border-color .15s ease, background-color .15s ease;
}

.dropzone:hover {
    border-color: var(--accent);
    background: var(--accent-soft);
}

.dropzone-icon {
    font-size: 26px;
    color: var(--text-muted);
}

.dropzone:hover .dropzone-icon { color: var(--accent); }

.dropzone-title {
    font-size: 13px;
    color: var(--text);
}

.dropzone-hint {
    font-size: 12px;
    color: var(--text-muted);
}

/* 原生 file input 视觉上隐藏但仍可聚焦/键盘操作 */
.dropzone input[type='file'] {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
    pointer-events: none;
}

/* ---------------- 反馈与进度 ---------------- */

.scan-feedback {
    margin-top: var(--sp-4);
    padding: 9px 13px;
    font-size: 12.5px;
    color: var(--text-2);
    border-left: 3px solid var(--accent);
    background: var(--accent-soft);
    border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

.scan-feedback.is-error {
    border-left-color: var(--danger);
    border-left-style: dashed;
    background: var(--danger-soft);
}

.scan-progress {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    margin-top: var(--sp-3);
    font-size: 12.5px;
    color: var(--text-2);
    font-family: var(--mono);
}

.spinner {
    width: 13px;
    height: 13px;
    flex: none;
    border: 2px solid var(--accent-ring);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin .7s linear infinite;
}

@keyframes spin {
    to { transform: rotate(360deg); }
}

/* ---------------- 规则库 ---------------- */

.rule-type-list {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-4);
    margin: var(--sp-3) 0 var(--sp-4);
    font-size: 12.5px;
    color: var(--text-2);
}

/* ---------------- 最近任务 ---------------- */

.clickable-rows :deep(.el-table__row) {
    cursor: pointer;
}
</style>
