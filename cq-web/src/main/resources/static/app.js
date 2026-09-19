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
    return `<span class="type">${esc(TYPE_LABELS[type] || type || '')}</span>`;
}

function fmtTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return esc(value);
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
        + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 简短提示条 */
function flash(element, message, isError = false) {
    element.hidden = false;
    element.textContent = message;
    element.style.borderLeftWidth = isError ? '6px' : '3px';
    element.style.borderLeftStyle = isError ? 'dashed' : 'solid';
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
            <tr>
                <td class="cell-line">#${task.id}</td>
                <td class="cell-file">${esc(task.targetPath)}</td>
                <td><span class="badge">${esc(task.mode)}</span></td>
                <td><span class="badge">${esc(task.status)}</span></td>
                <td class="cell-line">${task.fileCount}</td>
                <td class="cell-line">${task.issueCount}</td>
                <td class="cell-line">${task.durationMs == null ? '—' : task.durationMs + ' ms'}</td>
                <td class="cell-line">${fmtTime(task.createTime)}</td>
            </tr>`).join('');
        container.innerHTML = `
            <table>
                <thead><tr>
                    <th>ID</th><th>扫描路径</th><th>模式</th><th>状态</th>
                    <th>文件</th><th>问题</th><th>耗时</th><th>创建时间</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
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
    for (let attempt = 0; attempt < 120; attempt++) {
        const task = await api(`/api/scan/tasks/${taskId}`);
        if (task.status === 'SUCCESS') {
            flash(feedback, `扫描完成：${task.fileCount} 个文件，发现 ${task.issueCount} 个问题，`
                + `耗时 ${task.durationMs} ms`);
            // 扫描结束后顺手生成报告，让报告页立刻有数据
            await api(`/api/scan/tasks/${taskId}/report`, { method: 'POST' }).catch(() => null);
            await loadRecentTasks();
            await loadStatus();
            switchView('issues');
            return;
        }
        if (task.status === 'FAILED') {
            flash(feedback, `扫描失败：${task.errorMessage || '未知原因'}`, true);
            return;
        }
        await new Promise((resolve) => setTimeout(resolve, 700));
    }
    flash(feedback, '扫描仍在进行，请稍后在任务列表中查看', true);
}

/* ---------------- 问题列表 ---------------- */

async function loadTaskOptions() {
    const select = document.getElementById('filterTask');
    if (select.options.length > 1) return;
    try {
        const tasks = await api('/api/scan/tasks?limit=50');
        for (const task of tasks) {
            const option = document.createElement('option');
            option.value = task.id;
            option.textContent = `#${task.id} ${task.targetPath} (${task.issueCount} 个问题)`;
            select.appendChild(option);
        }
    } catch (error) {
        // 任务列表拉取失败不影响问题查询
    }
}

async function loadIssues() {
    const container = document.getElementById('issueTable');
    const params = new URLSearchParams();
    const taskId = document.getElementById('filterTask').value;
    const type = document.getElementById('filterType').value;
    const severity = document.getElementById('filterSeverity').value;
    if (taskId) params.set('taskId', taskId);
    if (type) params.set('type', type);
    if (severity) params.set('severity', severity);
    params.set('limit', '1000');

    try {
        const issues = await api(`/api/issues?${params.toString()}`);
        document.getElementById('issueCount').textContent = `共 ${issues.length} 条`;
        renderIssueMetrics(issues);
        if (!issues.length) {
            container.innerHTML = '<p class="empty">没有符合条件的问题</p>';
            return;
        }
        const rows = issues.map((issue) => `
            <tr class="is-clickable" data-issue-id="${issue.id}">
                <td>${severityBadge(issue.severity)}</td>
                <td>${typeLabel(issue.type)}</td>
                <td class="cell-file">${esc(issue.filePath)}</td>
                <td class="cell-line">${issue.line}</td>
                <td class="cell-rule">${esc(issue.ruleId)}</td>
                <td>${esc(issue.message)}</td>
                <td class="cell-line">${issue.confidence.toFixed(2)}</td>
            </tr>`).join('');
        container.innerHTML = `
            <table>
                <thead><tr>
                    <th>严重级</th><th>维度</th><th>文件</th><th>行</th>
                    <th>规则</th><th>问题描述</th><th>置信度</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
        container.querySelectorAll('tr.is-clickable').forEach((row) => {
            row.addEventListener('click', () => openDetail(row.dataset.issueId));
        });
    } catch (error) {
        container.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
    }
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

document.getElementById('applyFilter').addEventListener('click', loadIssues);

/* ---------------- 问题详情 ---------------- */

let currentIssueId = null;

async function openDetail(issueId) {
    currentIssueId = issueId;
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
                alert(`操作失败：${error.message}`);
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
                return `<span class="trend-seg trend-seg-${type}" style="width:${share}%"></span>`;
            }).join('');
            return `
                <div class="trend-row">
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
            <table>
                <thead><tr>
                    <th>任务</th><th>文件</th><th>问题</th>
                    <th>Bug/安全/性能/规范</th><th>B/C/M/m</th>
                    <th>密度</th><th>修复率</th><th>时间</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
    } catch (error) {
        chart.innerHTML = `<p class="empty">加载失败：${esc(error.message)}</p>`;
        table.innerHTML = '';
    }
}

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
            <table>
                <thead><tr><th>状态</th><th>规则 ID</th><th>名称</th>
                    <th>维度</th><th>默认严重级</th><th>置信度</th><th>说明</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
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
                    alert(`更新失败：${error.message}`);
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
            <table>
                <thead><tr><th>配置键</th><th>值</th><th>说明</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
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
                alert('阈值已保存，下次扫描生效');
            } catch (error) {
                alert(`保存失败：${error.message}`);
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
                <td class="cell-file">${esc(entry.filePattern)}</td>
                <td class="hint" style="margin:0">${esc(entry.reason || '')}</td>
                <td><button class="btn" data-ignore-id="${entry.id}">删除</button></td>
            </tr>`).join('');
        container.innerHTML = `
            <table>
                <thead><tr><th>规则</th><th>路径</th><th>原因</th><th></th></tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
        container.querySelectorAll('[data-ignore-id]').forEach((button) => {
            button.addEventListener('click', async () => {
                try {
                    await api(`/api/rules/ignores/${button.dataset.ignoreId}`, { method: 'DELETE' });
                    await loadIgnores();
                } catch (error) {
                    alert(`删除失败：${error.message}`);
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
        alert(`添加失败：${error.message}`);
    }
});

/* ---------------- 初始化 ---------------- */

(async function init() {
    await loadStatus();
    await loadRecentTasks();
    await loadTaskOptions();
})();
