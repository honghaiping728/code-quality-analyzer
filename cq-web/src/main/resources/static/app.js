/* =========================================================================
   智能代码质量分析 —— 前端逻辑（原生 JS，无构建步骤）
   ========================================================================= */

const TYPE_LABELS = {
    BUG: 'Bug',
    SECURITY: '安全',
    PERFORMANCE: '性能',
    STYLE: '规范',
};

const SEVERITY_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR'];

/* ---------------- 基础工具 ---------------- */

/** 统一请求封装：解开 Result 包装，非 200 一律抛出便于统一提示 */
async function api(path, options = {}) {
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

/** HTML 转义：所有来自后端的数据都要经过它再插入 DOM */
function esc(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function severityBadge(severity) {
    const value = esc(severity || 'MINOR');
    return `<span class="sev sev-${value}">${value}</span>`;
}

function typeLabel(type) {
    const value = esc(type || '');
    // 色点只是辅助线索，维度名始终以文字呈现
    return `<span class="type"><span class="type-dot type-dot-${value}"></span>`
        + `${esc(TYPE_LABELS[type] || type || '')}</span>`;
}

function fmtTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return esc(value);
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
        + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 简短提示条（有明确落点的场景，如扫描反馈） */
function flash(element, message, isError = false) {
    element.hidden = false;
    element.textContent = message;
    element.style.borderLeftWidth = isError ? '6px' : '3px';
    element.style.borderLeftStyle = isError ? 'dashed' : 'solid';
    element.style.borderLeftColor = isError ? 'var(--danger)' : 'var(--accent)';
}

/**
 * 全局提示条：替换散落在各处的 alert()
 * <p>
 * alert 会阻塞页面且样式不可控；这里统一成右下角浮层，自动消失、可点击关闭。
 * @param {string} message 提示文案
 * @param {string} [kind] success | error | info（默认 info）
 */
function toast(message, kind = 'info') {
    const stack = document.getElementById('toastStack');
    if (!stack) {
        return;
    }
    // 最多同时 3 条，避免连续操作时糊满屏幕
    while (stack.children.length >= 3) {
        stack.removeChild(stack.firstChild);
    }
    const item = document.createElement('div');
    item.className = `toast toast-${kind}`;
    item.textContent = message;
    item.addEventListener('click', () => item.remove());
    stack.appendChild(item);
    // 错误信息需要更多阅读时间
    const ttl = kind === 'error' ? 5000 : 3200;
    setTimeout(() => item.remove(), ttl);
}

/* ---------------- 视图切换 ---------------- */

function switchView(name) {
    document.querySelectorAll('.tab').forEach((tab) => {
        tab.classList.toggle('is-active', tab.dataset.view === name);
    });
    document.querySelectorAll('.view').forEach((view) => {
        view.classList.toggle('is-active', view.id === `view-${name}`);
    });
    if (name === 'issues') loadIssues();
    if (name === 'report') loadReport();
    if (name === 'rules') loadRules();
}

document.getElementById('tabs').addEventListener('click', (event) => {
    const tab = event.target.closest('.tab');
    if (tab) switchView(tab.dataset.view);
});

/* ---------------- 概览 ---------------- */

async function loadStatus() {
    const dot = document.getElementById('statusDot');
    const text = document.getElementById('statusText');
    try {
        const status = await api('/api/system/status');
        const dbOk = status.database === 'connected';
        dot.className = `dot ${dbOk ? 'is-ok' : 'is-bad'}`;
        text.textContent = dbOk
            ? `规则 ${status.ruleCount} 条 · ${status.llmAvailable ? status.llmClient : '离线启发式模式'}`
            : '数据库未连接';
        renderRuleMetrics(status.rulesByCategory, status.ruleCount);
    } catch (error) {
        dot.className = 'dot is-bad';
        text.textContent = '服务不可用';
    }
}

function renderRuleMetrics(byCategory, total) {
    const container = document.getElementById('ruleMetrics');
    if (!byCategory) {
        container.innerHTML = '<span class="hint">—</span>';
        return;
    }
    const cells = [`<div class="metric"><span class="metric-value">${total}</span>
        <span class="metric-label">规则总数</span></div>`];
    for (const type of ['BUG', 'SECURITY', 'PERFORMANCE', 'STYLE']) {
        cells.push(`<div class="metric"><span class="metric-value">${byCategory[type] || 0}</span>
            <span class="metric-label">${TYPE_LABELS[type]}</span></div>`);
    }
    container.innerHTML = cells.join('');
}

async function loadRecentTasks() {
    const container = document.getElementById('recentTasks');
    try {
        const tasks = await api('/api/scan/tasks?limit=8');
        if (!tasks.length) {
            container.innerHTML = '<p class="empty">暂无扫描记录</p>';
            return;
        }
        const rows = tasks.map((task) => `
            <tr class="is-clickable" data-task-id="${task.id}" title="点击查看该任务的问题">
                <td class="cell-line">#${task.id}</td>
                <td class="cell-file"><span class="clip" title="${esc(task.targetPath)}">${esc(task.targetPath)}</span></td>
                <td><span class="badge">${esc(task.mode)}</span></td>
                <td><span class="badge">${esc(task.status)}</span></td>
                <td class="cell-line">${task.fileCount}</td>
                <td class="cell-line">${task.issueCount}</td>
                <td class="cell-line">${task.durationMs == null ? '—' : task.durationMs + ' ms'}</td>
                <td class="cell-line">${fmtTime(task.createTime)}</td>
            </tr>`).join('');
        container.innerHTML = `
            <div class="table-wrap">
                <table>
                    <thead><tr>
                        <th>ID</th><th>扫描路径</th><th>模式</th><th>状态</th>
                        <th>文件</th><th>问题</th><th>耗时</th><th>创建时间</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
        // 点最近任务直接跳到问题列表并按该任务过滤，省去手动选筛选条件
        container.querySelectorAll('tr.is-clickable').forEach((row) => {
            row.addEventListener('click', async () => {
                await loadTaskOptions(true);
                document.getElementById('filterTask').value = row.dataset.taskId;
                issueState.page = 1;
                switchView('issues');
            });
        });
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
    }
}

document.getElementById('scanForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = document.getElementById('scanButton');
    const feedback = document.getElementById('scanFeedback');
    const path = document.getElementById('scanPath').value.trim();
    if (!path) {
        flash(feedback, '请输入扫描路径', true);
        return;
    }
    button.disabled = true;
    button.textContent = '扫描中…';
    flash(feedback, `已提交扫描任务：${path}，正在解析与检查…`);
    try {
        const task = await api('/api/scan/start', {
            method: 'POST',
            body: JSON.stringify({ path, mode: document.getElementById('scanMode').value }),
        });
        await pollTask(task.id, feedback);
    } catch (error) {
        flash(feedback, `扫描失败：${error.message}`, true);
    } finally {
        button.disabled = false;
        button.textContent = '开始扫描';
    }
});

/** 轮询任务直到结束，让用户看到进度而非干等 */
async function pollTask(taskId, feedback) {
    const startedAt = Date.now();
    startScanProgress('正在扫描…');
    try {
        for (let attempt = 0; attempt < 120; attempt++) {
            const task = await api(`/api/scan/tasks/${taskId}`);
            if (task.status === 'SUCCESS') {
                flash(feedback, `扫描完成：${task.fileCount} 个文件，发现 ${task.issueCount} 个问题，`
                    + `耗时 ${task.durationMs} ms`);
                // 扫描结束后顺手生成报告，让报告页立刻有数据
                await api(`/api/scan/tasks/${taskId}/report`, { method: 'POST' }).catch(() => null);
                await loadRecentTasks();
                await loadStatus();
                // 先把列表聚焦到本次任务，再切视图（切视图会触发 loadIssues，
                // 此时筛选条件已就位，展示的就是刚刚扫描出的结果）
                await focusTask(taskId);
                switchView('issues');
                return;
            }
            if (task.status === 'FAILED') {
                flash(feedback, `扫描失败：${task.errorMessage || '未知原因'}`, true);
                return;
            }
            const seconds = Math.floor((Date.now() - startedAt) / 1000);
            updateScanProgress(`正在扫描… 已用时 ${seconds} 秒 · 已扫描 ${task.fileCount ?? 0} 个文件`
                + ` · 暂发现 ${task.issueCount ?? 0} 个问题`);
            await new Promise((resolve) => setTimeout(resolve, 700));
        }
        flash(feedback, '扫描仍在进行，请稍后在任务列表中查看', true);
    } finally {
        // api() 抛错时也必须收起进度条，否则会留下一个永远转圈的假状态
        stopScanProgress();
    }
}

/** 扫描进度条：文案跟随既有 700ms 轮询更新，不额外起计时器 */
function startScanProgress(text) {
    document.getElementById('scanProgressText').textContent = text;
    document.getElementById('scanProgress').hidden = false;
    document.getElementById('statusDot').classList.add('is-busy');
}

function updateScanProgress(text) {
    document.getElementById('scanProgressText').textContent = text;
}

function stopScanProgress() {
    document.getElementById('scanProgress').hidden = true;
    document.getElementById('statusDot').classList.remove('is-busy');
}

/* ---------------- 输入方式切换 ---------------- */

document.getElementById('sourceTabs').addEventListener('click', (event) => {
    const tab = event.target.closest('.source-tab');
    if (!tab) return;
    document.querySelectorAll('.source-tab').forEach((item) => {
        item.classList.toggle('is-active', item === tab);
    });
    document.querySelectorAll('.source-pane').forEach((pane) => {
        pane.classList.toggle('is-active', pane.id === `pane-${tab.dataset.source}`);
    });
    document.getElementById('scanFeedback').hidden = true;
});

/* ---------------- 上传本地文件 ---------------- */

document.getElementById('uploadForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = document.getElementById('uploadFiles');
    const button = document.getElementById('uploadButton');
    const feedback = document.getElementById('scanFeedback');
    if (!input.files || input.files.length === 0) {
        flash(feedback, '请先选择要审查的 .java 文件', true);
        return;
    }
    const form = new FormData();
    for (const file of input.files) {
        form.append('files', file);
    }
    button.disabled = true;
    button.textContent = '上传中…';
    flash(feedback, `已上传 ${input.files.length} 个文件，正在解析与检查…`);
    try {
        // 用 FormData 上传时不能手动设置 Content-Type，
        // 否则会覆盖掉浏览器生成的 multipart boundary，服务端将无法解析
        const response = await fetch('/api/scan/upload', { method: 'POST', body: form });
        const body = await response.json();
        if (body.code !== 200) {
            throw new Error(body.message || '上传失败');
        }
        if (body.message && body.message !== 'success') {
            flash(feedback, body.message);
        }
        input.value = '';
        await pollTask(body.data.id, feedback);
    } catch (error) {
        flash(feedback, `上传失败：${error.message}`, true);
    } finally {
        button.disabled = false;
        button.textContent = '上传并审查';
    }
});

/* ---------------- 粘贴代码审查 ---------------- */

document.getElementById('snippetForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = document.getElementById('snippetButton');
    const feedback = document.getElementById('scanFeedback');
    const source = document.getElementById('snippetSource').value;
    if (!source.trim()) {
        flash(feedback, '请先粘贴要审查的代码', true);
        return;
    }
    button.disabled = true;
    button.textContent = '审查中…';
    try {
        const task = await api('/api/scan/snippet', {
            method: 'POST',
            body: JSON.stringify({
                fileName: document.getElementById('snippetName').value.trim(),
                source,
            }),
        });
        await pollTask(task.id, feedback);
    } catch (error) {
        flash(feedback, `审查失败：${error.message}`, true);
    } finally {
        button.disabled = false;
        button.textContent = '审查这段代码';
    }
});

/* ---------------- 仓库地址扫描 ---------------- */

// 增量模式才需要提交范围，全量扫描下隐藏这两项，避免让人误以为它们也生效
const repoModeSelect = document.getElementById('repoMode');
repoModeSelect.addEventListener('change', () => {
    document.getElementById('repoCommitRow').hidden = repoModeSelect.value !== 'INCREMENTAL';
});

document.getElementById('repoForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = document.getElementById('repoButton');
    const feedback = document.getElementById('scanFeedback');
    const url = document.getElementById('repoUrl').value.trim();
    if (!url) {
        flash(feedback, '请输入 GitHub 仓库地址', true);
        return;
    }
    button.disabled = true;
    button.textContent = '扫描中…';
    flash(feedback, `已提交仓库扫描：${url}，正在在线拉取源码并分析…`);
    try {
        const task = await api('/api/scan/repo', {
            method: 'POST',
            body: JSON.stringify({
                url,
                branch: document.getElementById('repoBranch').value.trim(),
                mode: repoModeSelect.value,
                baseCommit: document.getElementById('repoBaseCommit').value.trim(),
                headCommit: document.getElementById('repoHeadCommit').value.trim(),
            }),
        });
        await pollTask(task.id, feedback);
    } catch (error) {
        flash(feedback, `仓库扫描失败：${error.message}`, true);
    } finally {
        button.disabled = false;
        button.textContent = '在线扫描';
    }
});

/* ---------------- 问题列表 ---------------- */

/**
 * 刷新任务下拉选项
 * @param {boolean} force 是否强制重建。首页初始化时只需拉一次，但扫描完成后
 *   必须重建，否则新任务根本不会出现在下拉里，用户也就无法按任务筛选
 */
async function loadTaskOptions(force = false) {
    const select = document.getElementById('filterTask');
    if (!force && select.options.length > 1) return;
    try {
        const tasks = await api('/api/scan/tasks?limit=50');
        const previous = select.value;
        while (select.options.length > 1) {
            select.remove(1);   // 保留「全部」，其余重建
        }
        for (const task of tasks) {
            const option = document.createElement('option');
            option.value = task.id;
            option.textContent = `#${task.id} ${task.targetPath} (${task.issueCount} 个问题)`;
            select.appendChild(option);
        }
        select.value = previous;
    } catch (error) {
        // 任务列表拉取失败不影响问题查询
    }
}

/**
 * 把问题列表聚焦到指定任务
 * <p>
 * 扫描结束后必须做这一步：否则列表会展示**所有历史任务的合集**，按文件名排序，
 * 刚扫描出的问题混在几十条旧记录里看不出来，用户会以为列表没有刷新。
 * @param {number} taskId 任务 ID
 */
async function focusTask(taskId) {
    await loadTaskOptions(true);
    const select = document.getElementById('filterTask');
    select.value = String(taskId);
    // 任务已超出下拉上限时不强行筛选，退回「全部」以免出现空白列表
    if (select.value !== String(taskId)) {
        select.value = '';
    }
}

/**
 * 问题列表状态
 * <p>
 * 数据仍是一次请求取回（limit=1000，接口未变），关键词过滤与分页都在客户端完成——
 * 后端没有 keyword 参数，也不值得为一个前端展示需求加接口。
 */
const issueState = { items: [], page: 1, pageSize: 20 };

/** 最近一次查看的问题：返回列表时高亮该行（页码无需记录，切视图不会重置分页状态） */
const lastIssuePage = { selectedId: null };

/**
 * 加载问题列表
 * @param {boolean} [resetPage] 筛选条件变化时重置到第 1 页；单纯的视图切换保留当前页
 */
async function loadIssues(resetPage = false) {
    const container = document.getElementById('issueTable');
    const params = new URLSearchParams();
    const taskId = document.getElementById('filterTask').value;
    const type = document.getElementById('filterType').value;
    const severity = document.getElementById('filterSeverity').value;
    if (taskId) params.set('taskId', taskId);
    if (type) params.set('type', type);
    if (severity) params.set('severity', severity);
    params.set('limit', '1000');

    if (resetPage) {
        issueState.page = 1;
    }
    issueState.pageSize = Number(document.getElementById('pageSize').value) || 20;

    try {
        const issues = await api(`/api/issues?${params.toString()}`);
        issueState.items = applyIssueKeyword(issues);
        renderIssueList();
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
        document.getElementById('issuePagination').innerHTML = '';
    }
}

/** 客户端关键词过滤：文件路径 / 问题描述 / 规则 ID */
function applyIssueKeyword(issues) {
    const keyword = document.getElementById('filterKeyword').value.trim().toLowerCase();
    if (!keyword) {
        return issues;
    }
    return issues.filter((issue) =>
        (issue.filePath || '').toLowerCase().includes(keyword)
        || (issue.message || '').toLowerCase().includes(keyword)
        || (issue.ruleId || '').toLowerCase().includes(keyword));
}

function renderIssueList() {
    const items = issueState.items;
    document.getElementById('issueCount').textContent = `共 ${items.length} 条`;
    // 统计基于过滤后的全集，而不是当前这一页
    renderIssueMetrics(items);
    renderIssueRows(items);
    renderPagination(items.length);
}

function renderIssueRows(items) {
    const container = document.getElementById('issueTable');
    if (!items.length) {
        container.innerHTML = '<p class="empty">没有符合条件的问题</p>';
        return;
    }
    const size = issueState.pageSize;
    const pages = Math.max(1, Math.ceil(items.length / size));
    // 数据变少时把页码夹回有效范围，避免停在空页
    issueState.page = Math.min(Math.max(1, issueState.page), pages);
    const start = (issueState.page - 1) * size;
    const rows = items.slice(start, start + size).map((issue) => `
        <tr class="is-clickable${String(issue.id) === String(lastIssuePage.selectedId) ? ' is-selected' : ''}"
            data-issue-id="${issue.id}">
            <td>${severityBadge(issue.severity)}</td>
            <td>${typeLabel(issue.type)}</td>
            <td class="cell-file"><span class="clip" title="${esc(issue.filePath)}">${esc(issue.filePath)}</span></td>
            <td class="cell-line">${issue.line}</td>
            <td class="cell-rule">${esc(issue.ruleId)}</td>
            <td class="cell-msg"><span class="clip" title="${esc(issue.message)}">${esc(issue.message)}</span></td>
            <td class="cell-line">${Number(issue.confidence ?? 0).toFixed(2)}</td>
        </tr>`).join('');
    container.innerHTML = `
        <div class="table-wrap">
            <table>
                <thead><tr>
                    <th>严重级</th><th>维度</th><th>文件</th><th>行</th>
                    <th>规则</th><th>问题描述</th><th>置信度</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
    container.querySelectorAll('tr.is-clickable').forEach((row) => {
        row.addEventListener('click', () => openDetail(row.dataset.issueId));
    });
}

/** 分页条：页码窗口最多 7 个，两端用省略号收拢 */
function renderPagination(total) {
    const pager = document.getElementById('issuePagination');
    const size = issueState.pageSize;
    const pages = Math.max(1, Math.ceil(total / size));
    if (total <= size) {
        pager.innerHTML = '';
        return;
    }
    const page = issueState.page;
    const from = (page - 1) * size + 1;
    const to = Math.min(page * size, total);
    const parts = [
        `<button type="button" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button>`,
    ];
    for (const item of paginationWindow(page, pages)) {
        if (item === '...') {
            parts.push('<span>…</span>');
        } else {
            parts.push(`<button type="button" data-page="${item}"`
                + ` class="${item === page ? 'is-current' : ''}">${item}</button>`);
        }
    }
    parts.push(`<button type="button" data-page="${page + 1}" ${page >= pages ? 'disabled' : ''}>下一页</button>`);
    parts.push(`<span class="pager-range">第 ${from}-${to} 条 · 共 ${total} 条</span>`);
    pager.innerHTML = parts.join('');
    pager.querySelectorAll('button[data-page]').forEach((button) => {
        button.addEventListener('click', () => setIssuePage(Number(button.dataset.page)));
    });
}

function paginationWindow(page, pages, span = 7) {
    if (pages <= span) {
        return Array.from({ length: pages }, (_, index) => index + 1);
    }
    const half = Math.floor(span / 2);
    let start = Math.max(1, page - half);
    let end = Math.min(pages, start + span - 1);
    start = Math.max(1, end - span + 1);
    const result = [];
    if (start > 1) {
        result.push(1);
        if (start > 2) {
            result.push('...');
        }
    }
    for (let current = start; current <= end; current++) {
        result.push(current);
    }
    if (end < pages) {
        if (end < pages - 1) {
            result.push('...');
        }
        result.push(pages);
    }
    return result;
}

function setIssuePage(page) {
    issueState.page = page;
    // 重建表格容器会自然把内部滚动条带回顶部
    renderIssueRows(issueState.items);
    renderPagination(issueState.items.length);
}

function renderIssueMetrics(issues) {
    const byType = {};
    const bySeverity = {};
    for (const issue of issues) {
        byType[issue.type] = (byType[issue.type] || 0) + 1;
        bySeverity[issue.severity] = (bySeverity[issue.severity] || 0) + 1;
    }
    const cells = [`<div class="metric"><span class="metric-value">${issues.length}</span>
        <span class="metric-label">问题总数</span></div>`];
    for (const type of ['BUG', 'SECURITY', 'PERFORMANCE', 'STYLE']) {
        cells.push(`<div class="metric"><span class="metric-value">${byType[type] || 0}</span>
            <span class="metric-label">${TYPE_LABELS[type]}</span></div>`);
    }
    for (const severity of SEVERITY_ORDER) {
        cells.push(`<div class="metric"><span class="metric-value">${bySeverity[severity] || 0}</span>
            <span class="metric-label">${severity}</span></div>`);
    }
    document.getElementById('issueMetrics').innerHTML = cells.join('');
}

document.getElementById('applyFilter').addEventListener('click', () => loadIssues(true));
document.getElementById('filterKeyword').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
        event.preventDefault();
        loadIssues(true);
    }
});
// 换每页条数不需要重新拉数据，直接用已取回的集合重绘
document.getElementById('pageSize').addEventListener('change', () => {
    issueState.pageSize = Number(document.getElementById('pageSize').value) || 20;
    issueState.page = 1;
    renderIssueList();
});

/* ---------------- 问题详情 ---------------- */

let currentIssueId = null;

async function openDetail(issueId) {
    currentIssueId = issueId;
    // 记住点进来的是哪一行，返回列表时高亮它
    lastIssuePage.selectedId = issueId;
    switchView('detail');
    const container = document.getElementById('detailBody');
    container.innerHTML = '<p class="hint">加载中…</p>';
    try {
        const issue = await api(`/api/issues/${issueId}`);
        const suggestion = await api(`/api/suggestions/${issueId}`).catch(() => null);
        container.innerHTML = renderDetail(issue, suggestion);
        bindDetailActions(issue);
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
    }
}

// 详情页返回：走 switchView 而不是 history.back()，避免依赖浏览器历史栈
document.getElementById('detailBack').addEventListener('click', () => switchView('issues'));

function renderDetail(issue, suggestion) {
    const snippet = issue.codeSnippet
        ? `<pre>${esc(issue.codeSnippet)}</pre>`
        : '<p class="hint">未记录代码片段</p>';

    let suggestionBlock = '<p class="hint">尚未生成修复建议，点击下方按钮生成。</p>';
    if (suggestion) {
        const reference = suggestion.confidence < 0.6
            ? '<span class="badge">供参考</span>' : '';
        const diffHtml = suggestion.diff
            ? renderDiff(suggestion.diff)
            : '<p class="hint">该问题为语义级问题，未能给出确定性补丁，请参考上方修复方向。</p>';
        suggestionBlock = `
            <div class="section-title">修复建议 ${reference}
                <span class="badge">${esc(suggestion.source)}</span>
                <span class="badge">置信度 ${Number(suggestion.confidence).toFixed(2)}</span>
            </div>
            ${diffHtml}
            ${suggestion.explanation
                ? `<div class="section-title">说明</div><pre>${esc(suggestion.explanation)}</pre>` : ''}
            <div class="toolbar">
                <button class="btn" data-action="accept">采纳建议</button>
                <button class="btn" data-action="false-positive">标为误报</button>
            </div>`;
    }

    return `
        <div class="detail-head">
            <div>${severityBadge(issue.severity)} ${typeLabel(issue.type)}
                <span class="badge">${esc(issue.status)}</span></div>
            <h2>${esc(issue.message)}</h2>
            <div class="detail-meta">${esc(issue.filePath)}:${issue.line} ·
                ${esc(issue.ruleId)} · 置信度 ${Number(issue.confidence).toFixed(2)}</div>
        </div>
        <div class="panel">
            <dl class="detail-grid">
                <dt>规则 ID</dt><dd class="mono">${esc(issue.ruleId)}</dd>
                <dt>来源</dt><dd class="mono">${esc(issue.source)}</dd>
                <dt>代码行哈希</dt><dd class="mono">${esc((issue.lineHash || '').slice(0, 16) || '—')}</dd>
            </dl>
            <div class="section-title">源代码</div>
            ${snippet}
            ${issue.suggestion ? `<div class="section-title">规则给出的修复方向</div>
                <pre>${esc(issue.suggestion)}</pre>` : ''}
        </div>
        <div class="panel" id="suggestionPanel">
            <h2>修复建议</h2>
            ${suggestionBlock}
            ${suggestion ? '' : '<div class="toolbar"><button class="btn-primary" data-action="generate">生成修复建议</button></div>'}
        </div>`;
}

/** 把统一 diff 渲染成带 +/- 前缀与灰度底的行 */
function renderDiff(diff) {
    const lines = diff.split('\n').map((line) => {
        let cls = 'diff-line';
        if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) {
            cls += ' diff-meta';
        } else if (line.startsWith('+')) {
            cls += ' diff-add';
        } else if (line.startsWith('-')) {
            cls += ' diff-del';
        }
        return `<span class="${cls}">${esc(line) || '&nbsp;'}</span>`;
    }).join('');
    return `<pre>${lines}</pre>`;
}

function bindDetailActions(issue) {
    document.querySelectorAll('#detailBody [data-action]').forEach((button) => {
        button.addEventListener('click', async () => {
            const action = button.dataset.action;
            button.disabled = true;
            try {
                if (action === 'generate') {
                    await api(`/api/suggestions/${issue.id}`, { method: 'POST' });
                } else if (action === 'accept') {
                    await api('/api/suggestions/feedback', {
                        method: 'POST',
                        body: JSON.stringify({
                            issueId: issue.id, ruleId: issue.ruleId, action: 'ACCEPT',
                            comment: '开发者采纳了该建议',
                        }),
                    });
                    await api(`/api/issues/${issue.id}/status`, {
                        method: 'PATCH',
                        body: JSON.stringify({ status: 'FIXED' }),
                    });
                } else if (action === 'false-positive') {
                    await api('/api/suggestions/feedback', {
                        method: 'POST',
                        body: JSON.stringify({
                            issueId: issue.id, ruleId: issue.ruleId, action: 'FALSE_POSITIVE',
                            comment: '开发者标记为误报',
                        }),
                    });
                }
                await openDetail(issue.id);
            } catch (error) {
                toast(`操作失败：${error.message}`, 'error');
                button.disabled = false;
            }
        });
    });
}

/* ---------------- 报告与趋势 ---------------- */

async function loadReport() {
    const chart = document.getElementById('trendChart');
    const table = document.getElementById('reportTable');
    try {
        const reports = await api('/api/reports/trend?limit=20');
        if (!reports.length) {
            chart.innerHTML = '<p class="empty">暂无报告数据，请先执行一次扫描</p>';
            table.innerHTML = '<p class="hint">—</p>';
            return;
        }
        // 趋势按时间正序画，便于看出变化方向
        const ordered = [...reports].reverse();
        const max = Math.max(...ordered.map((r) => r.totalIssues || 1), 1);
        const legend = `
            <div class="legend">
                ${['BUG', 'SECURITY', 'PERFORMANCE', 'STYLE'].map((type) => `
                    <span class="legend-item">
                        <span class="legend-swatch legend-swatch-${type}"></span>${TYPE_LABELS[type]}
                    </span>`).join('')}
            </div>`;
        const bars = ordered.map((report) => {
            const total = report.totalIssues || 0;
            const width = (total / max) * 100;
            const segments = [
                ['BUG', report.bugCount], ['SECURITY', report.securityCount],
                ['PERFORMANCE', report.performanceCount], ['STYLE', report.styleCount],
            ].map(([type, count]) => {
                const share = total ? (count / total) * width : 0;
                // 每段都带可读数值：颜色只是辅助，悬浮即可看到确切条数
                const label = `${TYPE_LABELS[type]} ${count} 条 · 占比 ${total ? Math.round((count / total) * 100) : 0}%`;
                return `<span class="trend-seg trend-seg-${type}" style="width:${share}%"
                        title="${esc(label)}" data-tip="${esc(label)}"></span>`;
            }).join('');
            return `
                <div class="trend-row"
                     title="#${report.taskId} ${esc(fmtTime(report.createTime))} · 合计 ${total} 条">
                    <span class="trend-label" title="${esc(report.createTime || '')}">
                        #${report.taskId} ${esc((report.createTime || '').slice(5, 16))}</span>
                    <span class="trend-track">${segments}</span>
                    <span class="trend-value">${total}</span>
                </div>`;
        }).join('');
        chart.innerHTML = legend + bars;

        const rows = [...reports].map((report) => `
            <tr>
                <td class="cell-line">#${report.taskId}</td>
                <td class="cell-line">${report.totalFiles}</td>
                <td class="cell-line">${report.totalIssues}</td>
                <td class="cell-line">${report.bugCount} / ${report.securityCount}
                    / ${report.performanceCount} / ${report.styleCount}</td>
                <td class="cell-line">${report.blockerCount} / ${report.criticalCount}
                    / ${report.majorCount} / ${report.minorCount}</td>
                <td class="cell-line">${Number(report.issueDensity).toFixed(2)}</td>
                <td class="cell-line">${Number(report.fixRate * 100).toFixed(1)}%</td>
                <td class="cell-line">${fmtTime(report.createTime)}</td>
            </tr>`).join('');
        table.innerHTML = `
            <div class="table-wrap">
                <table>
                    <thead><tr>
                        <th>任务</th><th>文件</th><th>问题</th>
                        <th>Bug/安全/性能/规范</th><th>B/C/M/m</th>
                        <th>密度</th><th>修复率</th><th>时间</th>
                    </tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    } catch (error) {
        chart.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
        table.innerHTML = '';
    }
}

/**
 * 趋势图悬浮提示
 * <p>
 * 段上已有 title（无 JS 也能看），这里再补一个跟随鼠标的浮层，读数更顺手。
 * 事件委托绑在容器上，图表重新渲染后无需重新绑定。
 */
(function bindTrendTooltip() {
    const chart = document.getElementById('trendChart');
    const tip = document.getElementById('chartTip');
    chart.addEventListener('mousemove', (event) => {
        const segment = event.target.closest('.trend-seg');
        if (!segment || !segment.dataset.tip) {
            tip.hidden = true;
            return;
        }
        const panel = chart.closest('.chart-panel').getBoundingClientRect();
        tip.textContent = segment.dataset.tip;
        tip.hidden = false;
        tip.style.left = `${event.clientX - panel.left}px`;
        tip.style.top = `${event.clientY - panel.top}px`;
    });
    chart.addEventListener('mouseleave', () => { tip.hidden = true; });
})();

/* ---------------- 规则配置 ---------------- */

async function loadRules() {
    await Promise.all([loadRuleList(), loadThresholds(), loadIgnores()]);
}

async function loadRuleList() {
    const container = document.getElementById('ruleTable');
    try {
        const rules = await api('/api/rules');
        document.getElementById('ruleCount').textContent = `共 ${rules.length} 条`;
        const rows = rules.map((rule) => `
            <tr>
                <td><button class="toggle ${rule.enabled ? 'is-on' : ''}"
                        data-rule="${esc(rule.ruleId)}"
                        data-enabled="${rule.enabled}">${rule.enabled ? '启用' : '停用'}</button></td>
                <td class="cell-rule">${esc(rule.ruleId)}</td>
                <td>${esc(rule.ruleName)}</td>
                <td>${typeLabel(rule.category)}</td>
                <td class="cell-line">${esc(rule.severity || '')}</td>
                <td class="cell-line">${rule.confidence == null ? '' : Number(rule.confidence).toFixed(2)}</td>
                <td class="hint" style="margin:0">${esc(rule.description || '')}</td>
            </tr>`).join('');
        container.innerHTML = `
            <div class="table-wrap">
                <table>
                    <thead><tr><th>状态</th><th>规则 ID</th><th>名称</th>
                        <th>维度</th><th>默认严重级</th><th>置信度</th><th>说明</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
        container.querySelectorAll('.toggle').forEach((button) => {
            button.addEventListener('click', async () => {
                const enabled = button.dataset.enabled !== 'true';
                button.disabled = true;
                try {
                    await api(`/api/rules/${encodeURIComponent(button.dataset.rule)}`, {
                        method: 'PATCH',
                        body: JSON.stringify({ enabled }),
                    });
                    await loadRuleList();
                } catch (error) {
                    toast(`更新失败：${error.message}`, 'error');
                    button.disabled = false;
                }
            });
        });
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
    }
}

async function loadThresholds() {
    const container = document.getElementById('thresholdTable');
    try {
        const thresholds = await api('/api/rules/thresholds');
        const rows = thresholds.map((item) => `
            <tr>
                <td class="cell-rule">${esc(item.configKey)}</td>
                <td><input type="text" class="threshold-input" data-key="${esc(item.configKey)}"
                        value="${esc(item.configValue)}" style="flex:none;width:100%"></td>
                <td class="hint" style="margin:0">${esc(item.description || '')}</td>
            </tr>`).join('');
        container.innerHTML = `
            <div class="table-wrap">
                <table>
                    <thead><tr><th>配置键</th><th>值</th><th>说明</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
            <div class="toolbar"><button class="btn-primary" id="saveThresholds">保存阈值</button></div>`;
        document.getElementById('saveThresholds').addEventListener('click', async () => {
            const inputs = document.querySelectorAll('.threshold-input');
            try {
                for (const input of inputs) {
                    await api(`/api/rules/thresholds/${encodeURIComponent(input.dataset.key)}`, {
                        method: 'PATCH',
                        body: JSON.stringify({ value: input.value }),
                    });
                }
                toast('阈值已保存，下次扫描生效', 'success');
            } catch (error) {
                toast(`保存失败：${error.message}`, 'error');
            }
        });
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
    }
}

async function loadIgnores() {
    const container = document.getElementById('ignoreTable');
    try {
        const ignores = await api('/api/rules/ignores');
        if (!ignores.length) {
            container.innerHTML = '<p class="hint">暂无忽略项</p>';
            return;
        }
        const rows = ignores.map((entry) => `
            <tr>
                <td class="cell-rule">${esc(entry.ruleId || '（全部规则）')}</td>
                <td class="cell-file"><span class="clip" title="${esc(entry.filePattern)}">${esc(entry.filePattern)}</span></td>
                <td class="hint" style="margin:0">${esc(entry.reason || '')}</td>
                <td><button class="btn" data-ignore-id="${entry.id}">删除</button></td>
            </tr>`).join('');
        container.innerHTML = `
            <div class="table-wrap">
                <table>
                    <thead><tr><th>规则</th><th>路径</th><th>原因</th><th></th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
        container.querySelectorAll('[data-ignore-id]').forEach((button) => {
            button.addEventListener('click', async () => {
                try {
                    await api(`/api/rules/ignores/${button.dataset.ignoreId}`, { method: 'DELETE' });
                    await loadIgnores();
                } catch (error) {
                    toast(`删除失败：${error.message}`, 'error');
                }
            });
        });
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
    }
}

document.getElementById('ignoreForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
        await api('/api/rules/ignores', {
            method: 'POST',
            body: JSON.stringify({
                ruleId: document.getElementById('ignoreRule').value.trim() || null,
                filePattern: document.getElementById('ignorePattern').value.trim(),
                reason: document.getElementById('ignoreReason').value.trim(),
            }),
        });
        document.getElementById('ignoreForm').reset();
        await loadIgnores();
    } catch (error) {
        toast(`添加失败：${error.message}`, 'error');
    }
});

/* ---------------- 初始化 ---------------- */

(async function init() {
    await loadStatus();
    await loadRecentTasks();
    await loadTaskOptions();
})();
