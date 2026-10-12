import { expect, test } from '@playwright/test';

/**
 * 控制台端到端测试
 * <p>
 * 用真实浏览器驱动真实后端（前端是构建产物，后端需要一个已启动的 cq-web）。
 * 断言落在 DOM 行为与接口一致性上；像素级外观不做断言——那属于 screenshots.mjs
 * 的视觉核对，人眼看的，不该拿来当断言。
 */

/** 低置信度探针：同时命中 Bug、规范两类规则 */
const SNIPPET = `package t;
import java.math.BigDecimal;
public class FeProbe {
    public boolean cmp(BigDecimal a, BigDecimal b) { return a.equals(b); }
    public void loop(java.util.List<String> xs) {
        String s = "";
        for (String x : xs) { s += x; }
    }
}
`;

const PROBE_NAME = '__fe_probe__';
const PROBE_SECRET = 'sk-probe-000011112222';

test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('status-text')).toContainText('规则');
});

test.describe('页面骨架', () => {
    test('五个页签，概览默认激活，状态栏可用', async ({ page }) => {
        await expect(page.getByTestId('tabs').locator('.tab')).toHaveCount(5);
        await expect(page.locator('.tab[data-tab="overview"]')).toHaveClass(/is-active/);
        await expect(page.getByTestId('status-dot')).toHaveClass(/is-ok/);
        await expect(page.getByTestId('status-text')).toContainText('规则');
    });

    test('主题按钮存在且可切换', async ({ page }) => {
        await expect(page.getByTestId('theme-toggle')).toBeVisible();
    });
});

test.describe('概览', () => {
    test('四种来源齐全，切换后对应面板可见', async ({ page }) => {
        const tabs = page.getByTestId('source-tabs').locator('.source-tab');
        await expect(tabs).toHaveCount(4);

        for (const source of ['upload', 'snippet', 'repo', 'path']) {
            await page.locator(`.source-tab[data-source="${source}"]`).click();
            await expect(page.getByTestId(`pane-${source}`)).toBeVisible();
        }
    });

    test('上传表单只接受 .java 且支持多选', async ({ page }) => {
        await page.locator('.source-tab[data-source="upload"]').click();
        const input = page.getByTestId('upload-files');
        await expect(input).toHaveAttribute('accept', '.java');
        await expect(input).toHaveAttribute('multiple', '');
    });

    test('仓库增量模式才显示提交范围', async ({ page }) => {
        await page.locator('.source-tab[data-source="repo"]').click();
        await expect(page.getByTestId('repo-commit-row')).toBeHidden();

        await page.getByTestId('repo-mode').click();
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '增量扫描' }).click();
        await expect(page.getByTestId('repo-commit-row')).toBeVisible();

        await page.getByTestId('repo-mode').click();
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '全量扫描' }).click();
        await expect(page.getByTestId('repo-commit-row')).toBeHidden();
    });

    test('最近任务表已加载', async ({ page }) => {
        await expect(page.getByTestId('recent-tasks').locator('.el-table__row').first()).toBeVisible();
    });
});

test.describe('扫描链路', () => {
    test('粘贴代码 → 扫描完成 → 自动进入列表且聚焦本次任务', async ({ page }) => {
        await page.locator('.source-tab[data-source="snippet"]').click();
        // Element Plus 的 el-input 把未知属性透传到内层控件上，data-test 命中的就是 input 本身
        await page.getByTestId('snippet-name').fill('FeProbe.java');
        await page.getByTestId('snippet-source').fill(SNIPPET);
        await page.getByTestId('snippet-button').click();

        // 扫描完成会自动跳到问题列表
        await page.waitForURL(/\/issues/, { timeout: 45_000 });

        const rows = page.getByTestId('issue-table').locator('.el-table__row');
        await expect(rows.first()).toBeVisible();
        await expect(page.getByTestId('issue-table')).toContainText('FeProbe.java');

        // 关键回归：列表必须已按本次任务筛选，而不是把历史问题混进来。
        // Element Plus 的下拉把选中项渲染成文本而非 input 的 value，所以断言展示文本。
        await expect(page).toHaveURL(/task=\d+/);
        const taskId = new URL(page.url()).searchParams.get('task');
        await expect(page.getByTestId('filter-task')).toContainText(`#${taskId}`);
        await expect(page.getByTestId('issue-table')).not.toContainText('BadCode.java');
    });
});

test.describe('问题列表', () => {
    test('分页与关键词过滤', async ({ page }) => {
        await page.goto('/issues');
        await expect(page.getByTestId('issue-table').locator('.el-table__row').first()).toBeVisible();

        // 切到「全部任务」，保证样本量足够验证分页
        await page.getByTestId('filter-task').click();
        await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '全部' }).first().click();

        const rows = page.getByTestId('issue-table').locator('.el-table__row');
        await expect(rows).toHaveCount(20);   // 默认每页 20 条

        const firstBefore = await rows.first().innerText();
        await page.getByTestId('issue-pagination').locator('.el-pager li', { hasText: '2' }).first().click();
        await expect(rows.first()).not.toHaveText(firstBefore);

        // 关键词过滤：应只剩命中的文件
        await page.getByTestId('filter-keyword').fill('FeProbe');
        await page.getByTestId('apply-filter').click();
        await expect(page).toHaveURL(/keyword=FeProbe/);
        await expect(page.getByTestId('issue-table')).toContainText('FeProbe.java');
        await expect(page.getByTestId('issue-count')).toContainText('共');
    });
});

test.describe('问题详情', () => {
    test('从列表进入详情并可返回', async ({ page }) => {
        await page.goto('/issues');
        const firstRow = page.getByTestId('issue-table').locator('.el-table__row').first();
        await expect(firstRow).toBeVisible();
        await firstRow.click();

        await page.waitForURL(/\/issues\/\d+/);
        await expect(page.getByTestId('detail-back')).toBeVisible();
        await expect(page.locator('.detail-head h2')).toBeVisible();

        await page.getByTestId('detail-back').click();
        await page.waitForURL(/\/issues$/);
        await expect(page.getByTestId('issue-table')).toBeVisible();
    });
});

test.describe('报告与趋势', () => {
    test('趋势图与明细表都渲染出内容', async ({ page }) => {
        await page.goto('/report');
        await expect(page.getByTestId('trend-chart').locator('.trend-row').first()).toBeVisible();
        await expect(page.getByTestId('report-table').locator('.el-table__row').first()).toBeVisible();
        // 图例是四维度可辨识性的兜底：颜色不单独表意
        await expect(page.locator('.legend-item')).toHaveCount(4);
    });
});

test.describe('规则配置', () => {
    test('规则、阈值、忽略列表三块都能加载', async ({ page }) => {
        await page.goto('/rules');
        await expect(page.getByTestId('rule-table').locator('.el-table__row').first()).toBeVisible();
        await expect(page.getByTestId('threshold-table').locator('.el-table__row').first()).toBeVisible();
        // 忽略列表当前为空：必须落到空状态而不是停在"加载中"
        await expect(page.locator('section.panel').last()).toContainText(/暂无忽略项|el-table/);
        await expect(page.locator('section.panel').last()).not.toContainText('加载中');
    });

    test('规则可停用再启用', async ({ page }) => {
        await page.goto('/rules');
        // el-switch 的 data-test 落在根 div 上，开关状态是根上的 is-checked 类
        const firstToggle = page.getByTestId('rule-toggle').first();
        await expect(firstToggle).toBeVisible();
        const wasOn = await firstToggle.evaluate((el) => el.classList.contains('is-checked'));

        await firstToggle.click();
        await expect(firstToggle).toHaveClass(wasOn ? /^(?!.*is-checked)/ : /is-checked/);

        await firstToggle.click();   // 复原，避免影响后续运行
        await expect(firstToggle).toHaveClass(wasOn ? /is-checked/ : /^(?!.*is-checked)/);
    });
});

test.describe('大模型配置', () => {
    test('新增 → 掩码显示 → 启用 → 删除', async ({ page, request }) => {
        // 跑在真实库上：先清掉同名残留，保证可重复执行
        const list = await (await request.get('/api/llm/keys')).json();
        for (const row of list.data || []) {
            if (row.name === PROBE_NAME) {
                await request.delete(`/api/llm/keys/${row.id}`);
            }
        }

        await page.goto('/llm');
        await expect(page.getByTestId('llm-active-banner')).toBeVisible();

        await page.getByTestId('llm-name').fill(PROBE_NAME);
        await page.getByTestId('llm-base-url').fill('https://api.deepseek.com/v1');
        await page.getByTestId('llm-model').fill('deepseek-chat');
        await page.getByTestId('llm-secret').fill(PROBE_SECRET);
        await page.getByTestId('llm-save').click();

        const table = page.getByTestId('llm-key-table');
        await expect(table).toContainText(PROBE_NAME);
        // 密钥只以掩码出现，原文不得进入页面
        await expect(table).toContainText('sk-…2222');
        await expect(table).not.toContainText(PROBE_SECRET);
        await expect(page.getByTestId('llm-name')).toHaveValue('');

        // 启用状态与接口一致，且至多一条
        const afterAdd = await (await request.get('/api/llm/keys')).json();
        const activeCount = (afterAdd.data || []).filter((row) => row.active).length;
        await expect(table.getByTestId('llm-active-badge')).toHaveCount(activeCount);
        expect(activeCount).toBeLessThanOrEqual(1);

        // 删除并确认清理干净：确认按钮在弹窗内，必须限定作用域，
        // 否则会命中行内同名的「删除」按钮
        const probe = (afterAdd.data || []).find((row) => row.name === PROBE_NAME);
        await page.getByTestId(`llm-delete-${probe.id}`).click();
        const dialog = page.locator('.el-message-box');
        await expect(dialog).toBeVisible();
        await dialog.getByRole('button', { name: '删除' }).click();
        // 断言面板而不是表格：删掉最后一条后 el-table 整体卸载，
        // 对不存在的定位符做 not.toContainText 只会一直等到超时
        await expect(page.locator('section.panel').first()).not.toContainText(PROBE_NAME);
    });
});

test.describe('主题与路由', () => {
    test('切换暗色后 html 带 dark 类，且刷新后仍保持', async ({ page }) => {
        await page.getByTestId('theme-toggle').click();
        await expect(page.locator('html')).toHaveClass(/dark/);
        // 主色阶梯必须跟着换掉，否则组件悬停态会留着 Element 默认蓝
        const light9 = await page.locator('html').evaluate((el) =>
            getComputedStyle(el).getPropertyValue('--el-color-primary-light-9').trim());
        expect(light9).not.toBe('#ecf5ff');

        await page.reload();
        await expect(page.locator('html')).toHaveClass(/dark/);

        await page.getByTestId('theme-toggle').click();
        await expect(page.locator('html')).not.toHaveClass(/dark/);
    });

    test('深链直达：/issues 与 /llm 直接打开不 404', async ({ page }) => {
        for (const path of ['/issues', '/llm', '/report']) {
            const response = await page.goto(path);
            expect(response.status(), `${path} 应当由前端路由接管`).toBe(200);
            await expect(page.getByTestId('topbar')).toBeVisible();
        }
    });
});
