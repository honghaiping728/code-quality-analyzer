<script setup>
import { useToast } from '../composables/useToast';

const { toasts, dismiss } = useToast();
</script>

<template>
    <div class="toast-stack" aria-live="polite">
        <TransitionGroup name="toast">
            <div
                v-for="item in toasts"
                :key="item.id"
                class="toast"
                :class="`toast-${item.kind}`"
                role="status"
                data-test="toast"
                @click="dismiss(item.id)"
            >
                {{ item.message }}
            </div>
        </TransitionGroup>
    </div>
</template>

<style scoped>
.toast-stack {
    position: fixed;
    right: var(--sp-5);
    bottom: var(--sp-5);
    z-index: 3000;
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    pointer-events: none;
}

.toast {
    pointer-events: auto;
    cursor: pointer;
    max-width: 380px;
    padding: 10px 14px;
    font-size: 13px;
    line-height: 1.5;
    color: var(--text);
    background: var(--surface);
    border: 1px solid var(--border);
    border-left: 3px solid var(--accent);
    border-radius: var(--radius-sm);
    box-shadow: var(--shadow-3);
}

.toast-success { border-left-color: var(--ok); }
.toast-error { border-left-color: var(--danger); }

/* 从右侧滑入，位移小、时间短，不干扰阅读 */
.toast-enter-active { transition: opacity .2s ease, transform .2s ease; }
.toast-leave-active { transition: opacity .16s ease, transform .16s ease; position: absolute; right: 0; bottom: 0; }
.toast-enter-from { opacity: 0; transform: translateX(16px); }
.toast-leave-to { opacity: 0; transform: translateX(16px); }
.toast-move { transition: transform .2s ease; }
</style>
