import { ref } from 'vue';

import { systemApi } from '../api';

/**
 * 系统状态（数据库连通性 / 大模型可用性 / 规则总数）
 * <p>
 * 模块级共享：外壳状态栏与各视图读到同一份数据，大模型配置改动后刷新一次即可全站同步。
 */
const status = ref(null);
const failed = ref(false);
const loading = ref(false);

/** 并发调用只发一次请求：外壳初始化与视图挂载常同时触发 */
let inflight = null;

async function load() {
    if (inflight) {
        return inflight;
    }
    loading.value = true;
    inflight = systemApi
        .status()
        .then((data) => {
            status.value = data;
            failed.value = false;
            return data;
        })
        .catch(() => {
            failed.value = true;
            return null;
        })
        .finally(() => {
            loading.value = false;
            inflight = null;
        });
    return inflight;
}

export function useStatus() {
    return { status, failed, loading, load };
}
