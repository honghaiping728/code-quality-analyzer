import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/** 开发期把接口请求代理到本地后端（spring-boot:run 默认 8080） */
const backend = process.env.BACKEND_URL || 'http://127.0.0.1:8080';

/**
 * 构建产物直接落进 cq-web 的 classes 目录：
 * 这样 `mvn -pl cq-web spring-boot:run` 与打好的 fat jar 都能直接拿到页面，
 * 同时产物不进入源码目录（target/ 已被 gitignore），仓库里只有源码。
 */
const outDir = fileURLToPath(new URL('../cq-web/target/classes/static', import.meta.url));

export default defineConfig({
    // 部署在 servlet 上下文根，也是下面 proxy 键不加前缀的前提
    base: '/',
    plugins: [vue()],
    build: {
        outDir,
        // 目标目录在工程根之外，Vite 默认只清空根内目录，这里必须显式授权。
        // ⚠ 绝对不要把 outDir 放宽成 ../cq-web/target 或 ../cq-web/target/classes，
        //   那会把编译好的 class 一起删掉。
        emptyOutDir: true,
        // 显式钉住，避免 Vite 升级时目标悄悄漂移（同时决定了 color-mix 等特性的可用性）
        target: 'es2022',
        sourcemap: false,
        // Element Plus 全量引入后单 chunk 必然超过 500kB，这类警告没有信息量
        chunkSizeWarningLimit: 1500,
    },
    server: {
        port: 5173,
        // 端口被占用时直接失败，而不是静默换到 5174 让文档里的地址失效
        strictPort: true,
        proxy: {
            '/api': { target: backend, changeOrigin: true },
            '/test': { target: backend, changeOrigin: true },
        },
    },
});
