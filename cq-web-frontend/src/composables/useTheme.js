import { ref } from 'vue';

const STORAGE_KEY = 'cq-theme';

/** 初始值来自 index.html 的首屏前置脚本，避免闪烁 */
const theme = ref(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');

function apply(next) {
    theme.value = next;
    document.documentElement.dataset.theme = next;
    // Element Plus 的暗色变量挂在 .dark 上，两者必须同步
    document.documentElement.classList.toggle('dark', next === 'dark');
    try {
        localStorage.setItem(STORAGE_KEY, next);
    } catch (e) {
        // 隐私模式等写不了 localStorage：本次会话仍可用，只是不持久
    }
}

export function useTheme() {
    return {
        theme,
        toggle: () => apply(theme.value === 'dark' ? 'light' : 'dark'),
    };
}
