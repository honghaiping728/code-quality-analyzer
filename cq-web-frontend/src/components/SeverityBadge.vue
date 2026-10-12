<script setup>
/**
 * 严重级徽标
 * <p>
 * 用色底 + 白字（暗色下反过来用亮底深字），四档取值都经过对比度校验；
 * 文字即枚举原文，颜色只是辅助——不靠颜色单独表意。
 */
defineProps({
    severity: { type: String, default: 'MINOR' },
});
</script>

<template>
    <span class="sev" :class="`sev-${severity}`">{{ severity }}</span>
</template>

<style scoped>
.sev {
    display: inline-block;
    padding: 1px 8px;
    border-radius: var(--radius-pill);
    font-family: var(--mono);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: .02em;
    color: #fff;
    background: var(--neutral-badge);
    white-space: nowrap;
}

.sev-BLOCKER { background: var(--sev-blocker); }
.sev-CRITICAL { background: var(--sev-critical); }
.sev-MAJOR { background: var(--sev-major); }
.sev-MINOR { background: var(--sev-minor); }

/* 暗色档是亮色底，字改深色，保证对比度 */
:global(:root[data-theme='dark']) .sev,
:global(html.dark) .sev {
    color: #10151c;
}
</style>
