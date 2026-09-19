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

// ---------- 1. 页面基本结构 ----------
console.log('【页面结构】');
check('三个来源页签存在', window.document.querySelectorAll('.source-tab').length === 3);
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

// ---------- 汇总 ----------
const failed = results.filter((r) => !r.ok);
console.log(`\n=== ${results.length - failed.length}/${results.length} 通过 ===`);
if (failed.length) {
    console.log('失败项：');
    failed.forEach((f) => console.log(`  - ${f.name}  ${f.detail}`));
    process.exit(1);
}
