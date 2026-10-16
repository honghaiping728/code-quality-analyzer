/**
 * 后端接口封装
 * <p>
 * 统一解开 `Result{code,message,data}` 信封，非 200 一律抛出 Error（携带后端中文提示），
 * 由调用方 try/catch 后统一提示。相对路径同源请求，无需跨域配置。
 */

/**
 * @param {string} path 接口路径
 * @param {RequestInit} [options] 附加选项
 * @returns {Promise<any>} 信封中的 data
 */
async function request(path, options = {}) {
    // 注意：fetch 在调用时解析（不能提到模块顶层缓存），保持可被测试环境替换
    const response = await fetch(path, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    });
    const body = await response.json().catch(() => null);
    if (!body) {
        throw new Error(`响应不是合法 JSON (HTTP ${response.status})`);
    }
    if (body.code !== 200) {
        throw new Error(body.message || `请求失败 (HTTP ${response.status})`);
    }
    return body.data;
}

const json = (payload) => ({ body: JSON.stringify(payload) });

/** 查询参数：空值不参与拼接 */
function query(params) {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
        if (value !== '' && value !== null && value !== undefined) {
            search.set(key, value);
        }
    });
    const text = search.toString();
    return text ? `?${text}` : '';
}

export const systemApi = {
    status: () => request('/api/system/status'),
};

export const scanApi = {
    startTask: (payload) => request('/api/scan/start', { method: 'POST', ...json(payload) }),
    snippet: (payload) => request('/api/scan/snippet', { method: 'POST', ...json(payload) }),
    repo: (payload) => request('/api/scan/repo', { method: 'POST', ...json(payload) }),
    tasks: (limit) => request(`/api/scan/tasks?limit=${limit}`),
    task: (id) => request(`/api/scan/tasks/${id}`),
    buildReport: (id) => request(`/api/scan/tasks/${id}/report`, { method: 'POST' }),

    /**
     * 上传文件：必须绕过 request()——手动设置 Content-Type 会破坏 multipart 边界。
     * 返回整个信封而不是 data，调用方需要用到 message（跳过的不支持文件提示）。
     */
    async upload(files) {
        const form = new FormData();
        for (const file of files) {
            form.append('files', file);
        }
        const response = await fetch('/api/scan/upload', { method: 'POST', body: form });
        const body = await response.json().catch(() => null);
        if (!body) {
            throw new Error(`响应不是合法 JSON (HTTP ${response.status})`);
        }
        if (body.code !== 200) {
            throw new Error(body.message || `上传失败 (HTTP ${response.status})`);
        }
        return body;
    },
};

export const issueApi = {
    list: (params) => request(`/api/issues${query({ ...params, limit: params.limit ?? 1000 })}`),
    get: (id) => request(`/api/issues/${id}`),
    updateStatus: (id, status) => request(`/api/issues/${id}/status`, { method: 'PATCH', ...json({ status }) }),
};

export const suggestionApi = {
    /** 尚未生成时后端返回业务错误，调用方按"无建议"处理 */
    find: (issueId) => request(`/api/suggestions/${issueId}`),
    generate: (issueId) => request(`/api/suggestions/${issueId}`, { method: 'POST' }),
    feedback: (payload) => request('/api/suggestions/feedback', { method: 'POST', ...json(payload) }),
};

export const reportApi = {
    trend: (limit = 20) => request(`/api/reports/trend?limit=${limit}`),
};

export const ruleApi = {
    list: () => request('/api/rules'),
    setEnabled: (ruleId, enabled) =>
        request(`/api/rules/${encodeURIComponent(ruleId)}`, { method: 'PATCH', ...json({ enabled }) }),
    thresholds: () => request('/api/rules/thresholds'),
    setThreshold: (key, value) =>
        request(`/api/rules/thresholds/${encodeURIComponent(key)}`, { method: 'PATCH', ...json({ value }) }),
    ignores: () => request('/api/rules/ignores'),
    addIgnore: (payload) => request('/api/rules/ignores', { method: 'POST', ...json(payload) }),
    deleteIgnore: (id) => request(`/api/rules/ignores/${id}`, { method: 'DELETE' }),
};

export const llmApi = {
    list: () => request('/api/llm/keys'),
    add: (payload) => request('/api/llm/keys', { method: 'POST', ...json(payload) }),
    activate: (id) => request(`/api/llm/keys/${id}/activate`, { method: 'PATCH' }),
    remove: (id) => request(`/api/llm/keys/${id}`, { method: 'DELETE' }),
};
