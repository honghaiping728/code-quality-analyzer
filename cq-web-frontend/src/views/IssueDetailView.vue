<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import SeverityBadge from '../components/SeverityBadge.vue';
import TypeDot from '../components/TypeDot.vue';
import { issueApi, suggestionApi } from '../api';
import { formatConfidence } from '../constants';
import { toast } from '../composables/useToast';

const route = useRoute();
const router = useRouter();

const issue = ref(null);
const suggestion = ref(null);
const loading = ref(false);
const failed = ref('');
const acting = ref('');

async function load() {
    const id = route.params.id;
    loading.value = true;
    failed.value = '';
    try {
        issue.value = await issueApi.get(id);
        // 尚未生成建议是正常状态（后端返回业务错误），按"无建议"处理即可
        suggestion.value = await suggestionApi.find(id).catch(() => null);
    } catch (error) {
        issue.value = null;
        failed.value = error.message;
    } finally {
        loading.value = false;
    }
}

/** 差异逐行着色：文件头/区块头用弱化色，增删用底色；其余保持等宽正文 */
const diffLines = computed(() => {
    const text = suggestion.value?.diff;
    if (!text) {
        return [];
    }
    return String(text).split('\n').map((line) => {
        if (line.startsWith('+') && !line.startsWith('+++')) {
            return { text: line, kind: 'add' };
        }
        if (line.startsWith('-') && !line.startsWith('---')) {
            return { text: line, kind: 'del' };
        }
        if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) {
            return { text: line, kind: 'meta' };
        }
        return { text: line, kind: 'ctx' };
    });
});

const lowConfidence = computed(() => Number(suggestion.value?.confidence ?? 1) < 0.6);

async function run(action) {
    acting.value = action;
    try {
        if (action === 'generate') {
            await suggestionApi.generate(issue.value.id);
        } else if (action === 'accept') {
            await suggestionApi.feedback({
                issueId: issue.value.id,
                ruleId: issue.value.ruleId,
                action: 'ACCEPT',
                comment: '开发者采纳了该建议',
            });
            await issueApi.updateStatus(issue.value.id, 'FIXED');
        } else if (action === 'false-positive') {
            await suggestionApi.feedback({
                issueId: issue.value.id,
                ruleId: issue.value.ruleId,
                action: 'FALSE_POSITIVE',
                comment: '开发者标记为误报',
            });
        }
        await load();
    } catch (error) {
        toast(`操作失败：${error.message}`, 'error');
    } finally {
        acting.value = '';
    }
}

function goBack() {
    router.push('/issues');
}

watch(() => route.params.id, load);
onMounted(load);
</script>

<template>
    <div class="view-stack">
        <div class="detail-bar">
            <el-button data-test="detail-back" @click="goBack">← 返回问题列表</el-button>
        </div>

        <p v-if="loading" class="panel empty">加载中…</p>
        <p v-else-if="failed" class="panel empty">加载失败：{{ failed }}</p>
        <p v-else-if="!issue" class="panel empty">在问题列表中点击任意一行查看详情。</p>

        <template v-else>
            <section class="panel detail-head">
                <div class="detail-tags">
                    <SeverityBadge :severity="issue.severity" />
                    <TypeDot :type="issue.type" />
                    <span class="badge">{{ issue.status }}</span>
                </div>
                <h2>{{ issue.message }}</h2>
                <div class="detail-meta">
                    <span class="mono">{{ issue.filePath }}:{{ issue.line }}</span>
                    <span class="sep">·</span>
                    <span class="mono">{{ issue.ruleId }}</span>
                    <span class="sep">·</span>
                    <span>置信度 {{ formatConfidence(issue.confidence) }}</span>
                </div>
            </section>

            <section class="panel">
                <dl class="detail-grid">
                    <dt>规则 ID</dt>
                    <dd class="mono">{{ issue.ruleId }}</dd>
                    <dt>来源</dt>
                    <dd>{{ issue.source || '—' }}</dd>
                    <dt>代码行哈希</dt>
                    <dd class="mono">{{ issue.lineHash ? issue.lineHash.slice(0, 16) : '—' }}</dd>
                </dl>

                <div class="section-title">源代码</div>
                <pre v-if="issue.codeSnippet" class="code-block">{{ issue.codeSnippet }}</pre>
                <p v-else class="hint">未记录代码片段</p>

                <template v-if="issue.suggestion">
                    <div class="section-title">规则给出的修复方向</div>
                    <pre class="code-block">{{ issue.suggestion }}</pre>
                </template>
            </section>

            <section class="panel">
                <h2>修复建议</h2>

                <template v-if="!suggestion">
                    <p class="hint">尚未生成修复建议，点击下方按钮生成。</p>
                    <div class="toolbar">
                        <el-button
                            type="primary"
                            :loading="acting === 'generate'"
                            data-test="action-generate"
                            @click="run('generate')"
                        >
                            生成修复建议
                        </el-button>
                    </div>
                </template>

                <template v-else>
                    <div class="section-title suggestion-title">
                        <span>修复建议</span>
                        <span v-if="lowConfidence" class="badge">供参考</span>
                        <span class="badge">{{ suggestion.source }}</span>
                        <span class="badge">置信度 {{ formatConfidence(suggestion.confidence) }}</span>
                    </div>

                    <pre v-if="diffLines.length" class="diff-block" data-test="suggestion-diff"><span
                        v-for="(line, index) in diffLines"
                        :key="index"
                        class="diff-line"
                        :class="`diff-${line.kind}`"
                    >{{ line.text || ' ' }}</span></pre>
                    <p v-else class="hint">
                        该问题为语义级问题，未能给出确定性补丁，请参考上方修复方向。
                    </p>

                    <template v-if="suggestion.explanation">
                        <div class="section-title">说明</div>
                        <pre class="code-block">{{ suggestion.explanation }}</pre>
                    </template>

                    <div class="toolbar">
                        <el-button :loading="acting === 'accept'" data-test="action-accept" @click="run('accept')">
                            采纳建议
                        </el-button>
                        <el-button
                            :loading="acting === 'false-positive'"
                            data-test="action-false-positive"
                            @click="run('false-positive')"
                        >
                            标为误报
                        </el-button>
                    </div>
                </template>
            </section>
        </template>
    </div>
</template>

<style scoped>
.detail-bar { display: flex; }

.detail-head { border-left: 3px solid var(--border-strong); }

.detail-tags {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    margin-bottom: var(--sp-2);
}

.detail-head h2 {
    font-size: 18px;
    line-height: 1.45;
}

.detail-meta {
    margin-top: var(--sp-2);
    font-size: 12.5px;
    color: var(--text-muted);
}

.detail-meta .sep { margin: 0 8px; }

.detail-grid {
    display: grid;
    grid-template-columns: 96px 1fr;
    gap: 6px var(--sp-5);
    margin: 0;
}

.detail-grid dt {
    font-size: 12px;
    color: var(--text-muted);
}

.detail-grid dd { margin: 0; font-size: 13px; }

.suggestion-title {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
}

.code-block,
.diff-block {
    margin: 0;
    padding: var(--sp-4);
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    font-family: var(--mono);
    font-size: 12.5px;
    line-height: 1.7;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-word;
}

/* 差异视图刻意保持灰阶/浅底，不与四维度分类色抢注意力 */
.diff-block { display: flex; flex-direction: column; padding: 0; overflow: hidden; }

.diff-line {
    display: block;
    padding: 0 var(--sp-4);
    white-space: pre-wrap;
    word-break: break-word;
}

.diff-add { background: var(--diff-add-bg); color: var(--diff-add-ink); }
.diff-del { background: var(--diff-del-bg); color: var(--diff-del-ink); }
.diff-meta { color: var(--text-muted); background: var(--surface-2); }

.toolbar {
    display: flex;
    gap: var(--sp-2);
    margin-top: var(--sp-4);
}
</style>
