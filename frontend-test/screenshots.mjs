/**
 * 视觉核对：把每个视图在亮/暗两套主题下各截一张图，供人眼检查排版、配色与层次。
 *
 *   npm run shots              # 默认 http://127.0.0.1:8080
 *   BASE_URL=http://host:8080 npm run shots
 *
 * 输出到 frontend-test/shots/（已 gitignore）。截图只是给人看的，不做像素比对——
 * 字体与渲染在不同机器上必然有差异，拿它当断言只会得到一堆无意义的红。
 */
import { mkdir } from 'node:fs/promises';
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || process.env.BASE || 'http://127.0.0.1:8080';
const OUT = new URL('./shots/', import.meta.url);

/** 取一个真实存在的问题 id 用于详情页 */
async function sampleIssueId() {
    try {
        const body = await (await fetch(`${BASE}/api/issues?limit=1`)).json();
        return body?.data?.[0]?.id ?? null;
    } catch (e) {
        return null;
    }
}

const issueId = await sampleIssueId();

const VIEWS = [
    { name: 'overview', path: '/' },
    { name: 'issues', path: '/issues' },
    { name: 'detail', path: issueId ? `/issues/${issueId}` : null },
    { name: 'report', path: '/report' },
    { name: 'rules', path: '/rules' },
    { name: 'llm', path: '/llm' },
].filter((view) => view.path);

await mkdir(OUT, { recursive: true });

const browser = await chromium.launch({ args: ['--disable-dev-shm-usage'] });
const context = await browser.newContext({
    viewport: { width: 1440, height: 960 },
    deviceScaleFactor: 2,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
});

for (const theme of ['light', 'dark']) {
    const page = await context.newPage();
    await page.addInitScript((value) => {
        try {
            localStorage.setItem('cq-theme', value);
        } catch (e) {
            // 忽略
        }
    }, theme);

    for (const view of VIEWS) {
        await page.goto(`${BASE}${view.path}`, { waitUntil: 'networkidle' });
        // 数据是异步加载的，再等一拍让表格/图表渲染完
        await page.waitForTimeout(900);
        const file = new URL(`${view.name}-${theme}.png`, OUT);
        await page.screenshot({ path: file.pathname, fullPage: true });
        console.log(`  已截图 ${view.name} · ${theme}`);
    }
    await page.close();
}

await browser.close();
console.log(`\n截图目录：${OUT.pathname}`);
