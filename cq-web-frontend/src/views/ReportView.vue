<script setup>
import { computed, onMounted, ref } from 'vue';

import { reportApi } from '../api';
import { TYPE_LABELS, TYPE_ORDER, formatShortTime, formatTime } from '../constants';

const reports = ref([]);
const loading = ref(true);
const failed = ref('');

async function load() {
    loading.value = true;
    failed.value = '';
    try {
        reports.value = await reportApi.trend(20);
    } catch (error) {
        reports.value = [];
        failed.value = error.message;
    } finally {
        loading.value = false;
    }
}

/**
 * 图表按时间正序（旧 → 新）展示，方向感才成立；明细表保持接口返回的新 → 旧。
 */
const ordered = computed(() => [...reports.value].reverse());

const maxTotal = computed(() =>
    Math.max(1, ...ordered.value.map((row) => row.totalIssues || 0)));

/** 每行每段的宽度：先按全局最大总数归一，再按该行占比切分 */
function segments(row) {
    const total = row.totalIssues || 0;
    const width = (total / maxTotal.value) * 100;
    return TYPE_ORDER.map((type) => {
        const count = row[`${type.toLowerCase()}Count`] ?? 0;
        return {
            type,
            count,
            width: total ? (count / total) * width : 0,
            share: total ? Math.round((count / total) * 100) : 0,
        };
    });
}

/* ---------------- 悬浮提示 ---------------- */

const tip = ref({ visible: false, text: '', x: 0, y: 0 });

function showTip(event, text) {
    const host = event.currentTarget.closest('.chart-panel').getBoundingClientRect();
    tip.value = {
        visible: true,
        text,
        x: event.clientX - host.left,
        y: event.clientY - host.top,
    };
}

function hideTip() {
    tip.value.visible = false;
}

function rowTip(row) {
    return `#${row.taskId} ${formatTime(row.createTime)} · 合计 ${row.totalIssues || 0} 条`;
}

/** 轴标签只留「#任务号 + 月-日 时:分」，完整信息在 tooltip 里，避免标签被截断 */
function rowAxisLabel(row) {
    return `#${row.taskId} ${formatShortTime(row.createTime)}`;
}

function segmentTip(row, seg) {
    return `${TYPE_LABELS[seg.type]} ${seg.count} 条 · 占比 ${seg.share}%`;
}

onMounted(load);
</script>

<template>
    <div class="view-stack">
        <section class="panel chart-panel">
            <h2>质量趋势</h2>
            <p class="hint">
                按任务时间倒序展示问题总数与四维分布；图表为主视图，下方「报告明细」表为等价的数值视图。
            </p>

            <template v-if="ordered.length">
                <div class="legend">
                    <span v-for="type in TYPE_ORDER" :key="type" class="legend-item">
                        <span class="legend-swatch" :class="`swatch-${type}`" aria-hidden="true"></span>
                        <span>{{ TYPE_LABELS[type] }}</span>
                    </span>
                </div>

                <div class="trend" data-test="trend-chart" @mouseleave="hideTip">
                    <div v-for="row in ordered" :key="row.taskId" class="trend-row">
                        <span class="trend-label mono" :title="rowTip(row)">{{ rowAxisLabel(row) }}</span>
                        <span class="trend-track">
                            <span
                                v-for="seg in segments(row)"
                                :key="seg.type"
                                class="trend-seg"
                                :class="`seg-${seg.type}`"
                                :style="{ width: `${seg.width}%` }"
                                @mousemove="showTip($event, segmentTip(row, seg))"
                            ></span>
                        </span>
                        <span class="trend-value mono">{{ row.totalIssues || 0 }}</span>
                    </div>
                </div>
            </template>

            <p v-else-if="loading" class="empty">加载中…</p>
            <p v-else-if="failed" class="empty">加载失败：{{ failed }}</p>
            <p v-else class="empty">暂无报告数据，请先执行一次扫描</p>

            <div v-show="tip.visible" class="chart-tip" :style="{ left: `${tip.x}px`, top: `${tip.y}px` }">
                {{ tip.text }}
            </div>
        </section>

        <section class="panel">
            <h2>报告明细</h2>
            <el-table v-if="reports.length" :data="reports" data-test="report-table">
                <el-table-column label="任务" width="86">
                    <template #default="{ row }"><span class="mono">#{{ row.taskId }}</span></template>
                </el-table-column>
                <el-table-column label="文件" width="76">
                    <template #default="{ row }"><span class="mono">{{ row.totalFiles ?? '—' }}</span></template>
                </el-table-column>
                <el-table-column label="问题" width="76">
                    <template #default="{ row }"><span class="mono">{{ row.totalIssues ?? '—' }}</span></template>
                </el-table-column>
                <el-table-column
                    v-for="type in TYPE_ORDER"
                    :key="type"
                    :label="TYPE_LABELS[type]"
                    width="76"
                >
                    <template #default="{ row }">
                        <span class="mono">{{ row[`${type.toLowerCase()}Count`] ?? 0 }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="B/C/M/m" width="110">
                    <template #default="{ row }">
                        <span class="mono">{{ row.blockerCount ?? 0 }}/{{ row.criticalCount ?? 0 }}/{{ row.majorCount ?? 0 }}/{{ row.minorCount ?? 0 }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="密度" width="86">
                    <template #default="{ row }"><span class="mono">{{ Number(row.issueDensity ?? 0).toFixed(2) }}</span></template>
                </el-table-column>
                <el-table-column label="修复率" width="90">
                    <template #default="{ row }">
                        <span class="mono">{{ (Number(row.fixRate ?? 0) * 100).toFixed(1) }}%</span>
                    </template>
                </el-table-column>
                <el-table-column label="时间" min-width="170">
                    <template #default="{ row }"><span class="mono">{{ formatTime(row.createTime) }}</span></template>
                </el-table-column>
            </el-table>
            <p v-else-if="!loading && !failed" class="empty">—</p>
        </section>
    </div>
</template>

<style scoped>
.chart-panel { position: relative; }

.legend {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-4);
    margin: var(--sp-4) 0 var(--sp-3);
    font-size: 12.5px;
    color: var(--text-2);
}

.legend-item { display: inline-flex; align-items: center; gap: 6px; }

.legend-swatch {
    width: 10px;
    height: 10px;
    border-radius: 2px;
    flex: none;
}

/* 四维度分类色（亮/暗两档均经校验器验证） */
.swatch-BUG, .seg-BUG { background: var(--type-bug); }
.swatch-SECURITY, .seg-SECURITY { background: var(--type-security); }
.swatch-PERFORMANCE, .seg-PERFORMANCE { background: var(--type-performance); }
.swatch-STYLE, .seg-STYLE { background: var(--type-style); }

.trend {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: var(--sp-2);
}

.trend-row {
    display: grid;
    grid-template-columns: 210px 1fr 46px;
    align-items: center;
    gap: var(--sp-3);
}

.trend-label {
    font-size: 11.5px;
    color: var(--text-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* 轨道用极浅底 + 发丝线表现"零值基线"：本身不是数据，保持退让 */
.trend-track {
    display: flex;
    gap: 2px;               /* 段间 2px 表面间隙：靠轨道底色透出来 */
    height: 14px;
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 4px;
    overflow: hidden;
}

.trend-seg {
    height: 100%;
    transition: filter .12s ease;
}

.trend-seg:hover { filter: brightness(1.08); }

/* 数据端圆角、基线端贴齐：柱子是从左侧基线长出来的 */
.trend-track .trend-seg:last-child { border-radius: 0 4px 4px 0; }

.trend-value {
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    text-align: right;
    color: var(--text-2);
}

/* 反色提示条：亮色下深底浅字，暗色下自动翻转为浅底深字 */
.chart-tip {
    position: absolute;
    transform: translate(-50%, -130%);
    padding: 4px 9px;
    font-size: 12px;
    color: var(--surface);
    background: var(--text);
    border-radius: var(--radius-sm);
    white-space: nowrap;
    pointer-events: none;
    z-index: 60;
}

@media (max-width: 1100px) {
    .trend-row { grid-template-columns: 150px 1fr 40px; }
}
</style>
