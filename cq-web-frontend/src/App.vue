<script setup>
import { computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { Moon, Sunny } from '@element-plus/icons-vue';

import ToastStack from './components/ToastStack.vue';
import { useStatus } from './composables/useStatus';
import { useTheme } from './composables/useTheme';

const route = useRoute();
const { theme, toggle } = useTheme();
const { status, failed, load } = useStatus();

/**
 * 五个页签。问题详情不再是独立页签——它是问题列表的子路由，
 * 停留在详情时"问题列表"保持高亮：一个需要先点别处才有内容的页签不是导航目标。
 */
const TABS = [
    { to: '/', label: '概览', key: 'overview' },
    { to: '/issues', label: '问题列表', key: 'issues' },
    { to: '/report', label: '报告与趋势', key: 'report' },
    { to: '/rules', label: '规则配置', key: 'rules' },
    { to: '/llm', label: '大模型配置', key: 'llm' },
];

const activeTab = computed(() => route.meta.tab ?? 'overview');

const dbOk = computed(() => !failed.value && status.value?.database === 'connected');

const statusText = computed(() => {
    if (failed.value) {
        return '服务不可用';
    }
    if (!status.value) {
        return '连接中…';
    }
    if (status.value.database !== 'connected') {
        return '数据库未连接';
    }
    const llm = status.value.llmAvailable ? status.value.llmClient : '离线启发式模式';
    return `规则 ${status.value.ruleCount} 条 · ${llm}`;
});

onMounted(load);
</script>

<template>
    <header class="masthead">
        <div class="shell masthead-inner">
            <div>
                <h1>智能代码质量分析</h1>
                <p class="subtitle">AST 精确定位 · 四维规则检查 · 大模型成因分析与修复建议</p>
            </div>
        </div>
    </header>

    <div class="topbar" data-test="topbar">
        <div class="shell topbar-inner">
            <nav class="tabs" data-test="tabs">
                <RouterLink
                    v-for="tab in TABS"
                    :key="tab.key"
                    class="tab"
                    :class="{ 'is-active': activeTab === tab.key }"
                    :to="tab.to"
                    :data-tab="tab.key"
                >
                    {{ tab.label }}
                </RouterLink>
            </nav>

            <div class="system-status">
                <span class="dot" :class="dbOk ? 'is-ok' : 'is-bad'" data-test="status-dot"></span>
                <span class="status-text" data-test="status-text">{{ statusText }}</span>
                <button
                    class="theme-toggle"
                    type="button"
                    :title="theme === 'dark' ? '切换到亮色' : '切换到暗色'"
                    :aria-label="theme === 'dark' ? '切换到亮色' : '切换到暗色'"
                    data-test="theme-toggle"
                    @click="toggle"
                >
                    <el-icon :size="15">
                        <Sunny v-if="theme === 'dark'" />
                        <Moon v-else />
                    </el-icon>
                </button>
            </div>
        </div>
    </div>

    <main class="shell main">
        <RouterView v-slot="{ Component }">
            <Transition name="view-fade" mode="out-in">
                <component :is="Component" />
            </Transition>
        </RouterView>
    </main>

    <footer class="footer">
        <span>基于大语言模型的智能代码质量分析与自动修复建议系统</span>
    </footer>

    <ToastStack />
</template>

<style scoped>
/* ---------------- 页头 ---------------- */

.masthead {
    border-bottom: 1px solid var(--border);
    background: var(--surface);
}

.masthead-inner {
    padding-top: var(--sp-6);
    padding-bottom: var(--sp-5);
}

.masthead h1 {
    font-size: 21px;
    letter-spacing: -.01em;
}

.subtitle {
    margin: 5px 0 0;
    color: var(--text-muted);
    font-size: 12.5px;
    letter-spacing: .01em;
}

/* ---------------- 吸顶条：页签 + 状态 + 主题 ---------------- */

.topbar {
    position: sticky;
    top: 0;
    z-index: 100;
    border-bottom: 1px solid var(--border);
    /* 半透明 + 模糊，滚动时内容从下方穿过而不被硬切；不支持 color-mix 时退化为实底 */
    background: var(--surface);
    background: color-mix(in srgb, var(--surface) 86%, transparent);
    backdrop-filter: blur(10px);
}

.topbar-inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--sp-4);
}

.tabs {
    display: flex;
    gap: 2px;
    overflow-x: auto;
    scrollbar-width: none;
}

.tabs::-webkit-scrollbar { display: none; }

.tab {
    position: relative;
    padding: 13px 14px;
    color: var(--text-2);
    font-size: 13.5px;
    white-space: nowrap;
    transition: color .15s ease;
}

.tab:hover { color: var(--text); }

.tab.is-active {
    color: var(--accent);
    font-weight: 600;
}

/* 激活态用一条 2px 主色下划线，压在吸顶条的分隔线上 */
.tab.is-active::after {
    content: '';
    position: absolute;
    left: 10px;
    right: 10px;
    bottom: -1px;
    height: 2px;
    background: var(--accent);
    border-radius: 2px 2px 0 0;
}

.system-status {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    flex: none;
}

.status-text {
    font-size: 12px;
    color: var(--text-muted);
    font-family: var(--mono);
    white-space: nowrap;
}

.dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--border-strong);
    flex: none;
}

.dot.is-ok { background: var(--ok); }
.dot.is-bad { background: var(--danger); }

.theme-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    margin-left: var(--sp-1);
    color: var(--text-2);
    background: transparent;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: color .15s ease, border-color .15s ease, background-color .15s ease;
}

.theme-toggle:hover {
    color: var(--accent);
    border-color: var(--accent);
    background: var(--accent-soft);
}

/* ---------------- 主体 ---------------- */

.main {
    padding-top: var(--sp-6);
    padding-bottom: var(--sp-8);
}

.footer {
    max-width: var(--content-max);
    margin: 0 auto;
    padding: var(--sp-6);
    color: var(--text-muted);
    font-size: 12px;
    border-top: 1px solid var(--border);
}

@media (max-width: 860px) {
    .status-text { display: none; }
    .masthead-inner { padding-top: var(--sp-5); padding-bottom: var(--sp-4); }
    .main { padding-top: var(--sp-5); }
}
</style>
