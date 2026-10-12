import { ref } from 'vue';

/**
 * 轻提示
 * <p>
 * 模块级状态：任意位置（含组件之外）都能直接 `toast('...')`。
 * 行为沿用旧控制台：同时最多 3 条、点击可提前关闭、错误停留更久。
 */
const toasts = ref([]);
let seed = 0;

const DURATION = { error: 5000, success: 3200, info: 3200 };

export function dismissToast(id) {
    toasts.value = toasts.value.filter((item) => item.id !== id);
}

export function toast(message, kind = 'info') {
    const id = ++seed;
    toasts.value.push({ id, message: String(message ?? ''), kind });
    // 超出上限先挤掉最旧的一条，避免刷屏
    while (toasts.value.length > 3) {
        toasts.value.shift();
    }
    setTimeout(() => dismissToast(id), DURATION[kind] ?? 3200);
    return id;
}

export function useToast() {
    return { toasts, dismiss: dismissToast };
}
