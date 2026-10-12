<script setup>
/**
 * 指标条：一排等宽数字格
 * <p>
 * 大数字用等宽字体（本项目原有特色，数字对得齐），标签小字弱化；
 * 格子之间用发丝线分隔而不是各自的边框，避免"格子套格子"。
 */
defineProps({
    items: { type: Array, default: () => [] },
});
</script>

<template>
    <div class="metric-row" data-test="metric-row">
        <div v-for="item in items" :key="item.label" class="metric" :title="item.label">
            <span class="metric-value" :class="{ 'is-accent': item.accent }">{{ item.value }}</span>
            <span class="metric-label">{{ item.label }}</span>
        </div>
    </div>
</template>

<style scoped>
.metric-row {
    display: flex;
    flex-wrap: wrap;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    overflow: hidden;
    background: var(--surface);
}

/* min-width 取得较小，好让概览右侧那种窄栏里的 5 格也能排成一行而不折行 */
.metric {
    flex: 1 1 0;
    min-width: 86px;
    padding: 12px 14px;
    border-right: 1px solid var(--border);
}

.metric:last-child { border-right: none; }

.metric-value {
    display: block;
    font-family: var(--mono);
    font-size: 21px;
    font-weight: 650;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
}

.metric-value.is-accent { color: var(--accent); }

.metric-label {
    display: block;
    margin-top: 3px;
    font-size: 11px;
    color: var(--text-muted);
    letter-spacing: .04em;
}
</style>
