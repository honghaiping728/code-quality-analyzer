/**
 * 前端集成测试：用 jsdom 真实加载页面并驱动交互。
 * 服务端必须是已启动的 cq-web（默认 http://127.0.0.1:8080）。
 */
import { JSDOM, VirtualConsole } from 'jsdom';

const BASE = process.env.BASE || 'http://127.0.0.1:8080';
const results = [];
function check(name, ok, detail = '') {
    results.push({ name, ok, detail });
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

/** 把 node 的 fetch 桥接给 jsdom，并把相对路径补全为绝对地址 */
function bridgeFetch(window) {
    window.fetch = (input, init) => {
        const url = typeof input === 'string' && input.startsWith('/') ? BASE + input : input;
        return fetch(url, init);
    };
}

async function loadPage() {
    const html = await (await fetch(BASE + '/')).text();
    const virtualConsole = new VirtualConsole();
    virtualConsole.on('jsdomError', (e) => console.error('   [jsdom]', e.message));
    const dom = new JSDOM(html, {
        url: BASE + '/',
        runScripts: 'dangerously',
        resources: 'usable',
        virtualConsole,
        pretendToBeVisual: true,
    });
    bridgeFetch(dom.window);
    // 等外部 app.js 加载并执行完 init()
    await new Promise((r) => setTimeout(r, 2500));
    return dom;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

console.log(`\n=== 驱动 ${BASE}\n`);
const dom = await loadPage();
const { window } = dom;
const $ = (id) => window.document.getElementById(id);

// jsdom 里 alert 只是往虚拟控制台写一条警告，不会抛错；
// 打个桩记录调用，用来确认提示已全部换成 toast
window.__alerted = false;
window.alert = () => { window.__alerted = true; };

// ---------- 1. 页面基本结构 ----------
console.log('【页面结构】');
check('四个来源页签存在', window.document.querySelectorAll('.source-tab').length === 4);
check('概览页默认激活',
    $('view-overview')?.classList.contains('is-active') === true);

// ---------- 2. 初始加载：状态与任务下拉 ----------
console.log('\n【初始加载】');
check('状态栏已填充', ($('statusText')?.textContent || '').includes('规则'),
    $('statusText')?.textContent);
const initialOptions = $('filterTask')?.options.length || 0;
check('任务下拉已加载', initialOptions > 1, `${initialOptions} 个选项`);

// ---------- 3. 粘贴代码审查（核心流程） ----------
console.log('\n【粘贴代码 → 跳转列表】');
const snippet = `package t;
import java.math.BigDecimal;
public class FeProbe {
    public boolean cmp(BigDecimal a, BigDecimal b) { return a.equals(b); }
    public void loop(java.util.List<String> xs) {
        String s = "";
        for (String x : xs) { s += x; }
    }
}
`;
$('snippetName').value = 'FeProbe.java';
$('snippetSource').value = snippet;
$('snippetForm').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));

// 轮询最多 30 秒等待扫描完成并跳转
let jumped = false;
for (let i = 0; i < 60; i++) {
    await sleep(500);
    if ($('view-issues')?.classList.contains('is-active')) { jumped = true; break; }
}
check('扫描完成后自动切到问题列表', jumped);

// ---------- 4. 关键回归：列表必须聚焦到本次任务 ----------
console.log('\n【关键回归：列表是否聚焦到本次任务】');
const taskFilter = $('filterTask');
check('任务筛选已有选中值', taskFilter.value !== '',
    `当前值="${taskFilter.value}"`);
check('新任务已进入下拉选项',
    Array.from(taskFilter.options).some((o) => o.value === taskFilter.value && o.value !== ''),
    `选中 ${taskFilter.value}`);

const tableText = $('issueTable').textContent || '';
check('表格包含刚粘贴文件的问题', tableText.includes('FeProbe.java'),
    tableText.includes('FeProbe.java') ? '' : '未找到 FeProbe.java');
check('表格不含其他任务的文件',
    !tableText.includes('BadCode.java'),
    tableText.includes('BadCode.java') ? '混入了历史任务的问题（即本次修复的 bug）' : '');
check('统计栏已更新', ($('issueCount')?.textContent || '').includes('共'),
    $('issueCount')?.textContent);

// ---------- 5. 切到「全部」应能看到历史任务 ----------
console.log('\n【切回全部任务】');
taskFilter.value = '';
// 问题列表已分页（默认 20/页），库里 260 条时第 1 页看不到两份文件；
// 这里先把页大小调大，保持「不筛选时能同时看到新旧结果」这条断言的原本含义
$('pageSize').value = '500';
$('pageSize').dispatchEvent(new window.Event('change', { bubbles: true }));
$('applyFilter').dispatchEvent(new window.Event('click', { bubbles: true }));
await sleep(1500);
const allText = $('issueTable').textContent || '';
check('「全部」视图能同时看到新旧文件',
    allText.includes('FeProbe.java') && allText.includes('BadCode.java'));

// ---------- 6. 上传表单存在且可用 ----------
console.log('\n【上传文件表单】');
check('文件输入接受 .java', $('uploadFiles')?.getAttribute('accept') === '.java');
check('文件输入支持多选', $('uploadFiles')?.hasAttribute('multiple') === true);
check('上传按钮存在', !!$('uploadButton'));

// ---------- 7. 来源切换 ----------
console.log('\n【来源切换】');
window.document.querySelector('.source-tab[data-source="upload"]')
    .dispatchEvent(new window.Event('click', { bubbles: true }));
check('切到上传面板', $('pane-upload')?.classList.contains('is-active') === true);
check('目录面板已隐藏', $('pane-path')?.classList.contains('is-active') === false);

// ---------- 8. 仓库地址表单（只验结构，不触发真实拉取，避免依赖网络与限流） ----------
console.log('\n【仓库地址表单】');
window.document.querySelector('.source-tab[data-source="repo"]')
    .dispatchEvent(new window.Event('click', { bubbles: true }));
check('切到仓库面板', $('pane-repo')?.classList.contains('is-active') === true);
check('目录面板已隐藏', $('pane-path')?.classList.contains('is-active') === false);
check('仓库地址输入框存在', !!$('repoUrl'));
check('分支输入框存在', !!$('repoBranch'));
check('仓库扫描按钮存在', !!$('repoButton'));

// 增量模式才显示提交范围输入行
const repoMode = $('repoMode');
check('提交范围默认隐藏', $('repoCommitRow')?.hidden === true);
repoMode.value = 'INCREMENTAL';
repoMode.dispatchEvent(new window.Event('change', { bubbles: true }));
check('切到增量模式后提交范围出现', $('repoCommitRow')?.hidden === false);
repoMode.value = 'FULL';
repoMode.dispatchEvent(new window.Event('change', { bubbles: true }));
check('切回全量模式后提交范围隐藏', $('repoCommitRow')?.hidden === true);

// ---------- 9. 界面优化：吸顶 / 分页 / 过滤 / 提示 ----------
console.log('\n【布局与导航】');
check('吸顶条存在且承载页签', !!$('topbar') && $('topbar').contains($('tabs')));
check('服务状态已移入吸顶条', $('topbar').contains($('statusDot')));
check('每页条数选择器存在', !!$('pageSize'));
check('详情返回按钮存在', !!$('detailBack'));

console.log('\n【问题列表分页】');
$('pageSize').value = '20';
$('pageSize').dispatchEvent(new window.Event('change', { bubbles: true }));
await sleep(300);
const pageRows = window.document.querySelectorAll('#issueTable tbody tr').length;
check('每页 20 条生效', pageRows === 20, `${pageRows} 行`);
check('分页条显示总数与区间', ($('issuePagination')?.textContent || '').includes('共'));
const firstRowBefore = window.document.querySelector('#issueTable tbody tr')?.textContent || '';
window.document.querySelector('#issuePagination button[data-page="2"]')
    .dispatchEvent(new window.Event('click', { bubbles: true }));
await sleep(300);
const firstRowAfter = window.document.querySelector('#issueTable tbody tr')?.textContent || '';
check('翻到第 2 页内容变化', firstRowAfter !== '' && firstRowAfter !== firstRowBefore);
check('当前页按钮高亮', window.document.querySelector('#issuePagination button.is-current')?.textContent === '2');

console.log('\n【关键词过滤】');
$('filterKeyword').value = 'FeProbe';
$('applyFilter').dispatchEvent(new window.Event('click', { bubbles: true }));
await sleep(1500);
const filteredText = $('issueTable').textContent || '';
check('关键词过滤只保留命中文件',
    filteredText.includes('FeProbe.java') && !filteredText.includes('BadCode.java'));
check('过滤后统计同步', ($('issueCount')?.textContent || '').includes('共'));
$('filterKeyword').value = '';

console.log('\n【提示与进度】');
window.toast('界面优化测试提示', 'info');
check('toast 渲染到提示栈', ($('toastStack')?.textContent || '').includes('界面优化测试提示'));
check('扫描进度条元素存在', !!$('scanProgress'));
check('全程未触发 alert', window.__alerted !== true);

console.log('\n【详情返回】');
window.switchView('detail');
$('detailBack').dispatchEvent(new window.Event('click', { bubbles: true }));
await sleep(1500);
check('返回按钮切回问题列表', $('view-issues')?.classList.contains('is-active') === true);

// ---------- 汇总 ----------
const failed = results.filter((r) => !r.ok);
console.log(`\n=== ${results.length - failed.length}/${results.length} 通过 ===`);
if (failed.length) {
    console.log('失败项：');
    failed.forEach((f) => console.log(`  - ${f.name}  ${f.detail}`));
    process.exit(1);
}
