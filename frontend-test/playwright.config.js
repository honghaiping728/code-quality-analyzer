import { defineConfig, devices } from '@playwright/test';

/**
 * 地址约定：BASE_URL 优先，兼容旧 jsdom 用例用的 BASE。
 * webServer.url 必须是绝对地址，因此两处共用同一个值。
 */
const baseURL = process.env.BASE_URL || process.env.BASE || 'http://127.0.0.1:8080';

/** TestController 是最廉价的就绪探针：不碰数据库、不触发任何扫描 */
const readyUrl = `${baseURL}/test`;

export default defineConfig({
    testDir: './tests',
    outputDir: './test-results',

    // 整条链路上有异步扫描与轮询，单例超时给宽一些
    timeout: 60_000,
    expect: { timeout: 15_000 },

    // 共用一个后端与一个数据库：串行执行更稳，日志也更好读
    fullyParallel: false,
    workers: 1,

    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    reporter: [['list'], ['html', { open: 'never' }]],

    use: {
        baseURL,
        // 模板里统一用 data-test；Playwright 默认找 data-testid，
        // 不设这一项 getByTestId 会静默匹配不到任何元素
        testIdAttribute: 'data-test',
        locale: 'zh-CN',
        timezoneId: 'Asia/Shanghai',
        actionTimeout: 10_000,
        navigationTimeout: 20_000,
        screenshot: 'only-on-failure',
        trace: 'on-first-retry',
        launchOptions: {
            // 容器内 /dev/shm 通常只有 64MB，Chromium 默认会用满并崩溃
            args: ['--disable-dev-shm-usage'],
        },
    },

    projects: [
        {
            name: 'chromium',
            use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
        },
    ],

    /**
     * 本地通常已经起着服务（那样最好，起一次能跑很多轮）；没起就由 Playwright 拉起来。
     * 设成 false 会因为 8080 已被占用而直接报错。
     */
    webServer: {
        command: 'java -jar cq-web/target/cq-web-2.0.0.jar',
        cwd: '..',
        url: readyUrl,
        reuseExistingServer: true,
        timeout: 120_000,
        stdout: 'pipe',
        stderr: 'pipe',
    },
});
