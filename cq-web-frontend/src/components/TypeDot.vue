<script setup>
import { TYPE_LABELS } from '../constants';

/**
 * 四维度标识：色点 + 文字标签
 * <p>
 * 色点永远与文字同行出现——分类色在亮色表面上未达 3:1（dataviz 校验器给出的
 * 缓解要求即"可见标签"），因此颜色只是加速识别，不承担表意。
 */
defineProps({
    type: { type: String, default: '' },
});
</script>

<template>
    <span class="type">
        <span class="dot" :class="`dot-${type}`" aria-hidden="true"></span>
        <span>{{ TYPE_LABELS[type] || type }}</span>
    </span>
</template>

<style scoped>
.type {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
}

.dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex: none;
    background: var(--neutral-badge);
}

.dot-BUG { background: var(--type-bug); }
.dot-SECURITY { background: var(--type-security); }
.dot-PERFORMANCE { background: var(--type-performance); }
.dot-STYLE { background: var(--type-style); }
</style>
