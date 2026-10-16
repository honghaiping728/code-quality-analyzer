/** 四维度：中文标签与固定顺序（指标卡、图例、趋势段、筛选器四处共用同一顺序） */
export const TYPE_LABELS = {
    BUG: 'Bug',
    SECURITY: '安全',
    PERFORMANCE: '性能',
    STYLE: '规范',
};

export const TYPE_ORDER = ['BUG', 'SECURITY', 'PERFORMANCE', 'STYLE'];

/** 严重级由重到轻 */
export const SEVERITY_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR'];

export const SEVERITY_LABELS = {
    BLOCKER: 'Blocker',
    CRITICAL: 'Critical',
    MAJOR: 'Major',
    MINOR: 'Minor',
};

export const PAGE_SIZES = [20, 50, 100, 500];

/** 任务状态：原控制台直接显示枚举原文，这里保持一致的取值集合 */
export const TASK_STATUSES = ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'];

/**
 * 时间格式化：本地时区的 YYYY-MM-DD HH:mm:ss
 * <p>
 * 刻意不用 toLocaleString：后端以固定格式回传，前端按同一格式展示才可比对。
 */
export function formatTime(value) {
    if (!value) {
        return '—';
    }
    const date = new Date(String(value).replace(' ', 'T'));
    if (Number.isNaN(date.getTime())) {
        return String(value);
    }
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
        + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 趋势图轴标签：MM-DD HH:mm，比全量时间更省横向空间 */
export function formatShortTime(value) {
    const text = String(value ?? '');
    return text.length >= 16 ? text.slice(5, 16) : formatTime(value);
}

/** 置信度：两位小数，空值显示占位符 */
export function formatConfidence(value) {
    return value === null || value === undefined || value === '' ? '—' : Number(value).toFixed(2);
}

/**
 * 上传文件时可选的扩展名
 * <p>
 * 与后端 `com.cq.common.lang.Language` 枚举一一对应，改动需同步两侧：
 * 前端这份只用于文件选择框的过滤提示与列表展示，真正的准入判定始终在后端，
 * 拖拽进来的文件不会经过 accept 过滤，仍由服务端决定收不收。
 */
export const SUPPORTED_EXTENSIONS = [
    '.java',
    '.yml', '.yaml',
    '.properties', '.ini', '.env', '.cfg', '.conf',
    '.json', '.xml', '.toml',
    '.sql',
    '.sh', '.bash', '.zsh',
    '.js', '.jsx', '.mjs', '.cjs',
    '.ts', '.tsx',
    '.vue',
    '.py', '.go',
    '.css', '.scss', '.less',
    '.md',
    '.pem', '.key',
];

/** 扩展名 -> 语言展示名，用于问题列表里标注文件类型 */
const LANGUAGE_LABELS = {
    java: 'Java',
    yml: 'YAML', yaml: 'YAML',
    properties: '配置', ini: '配置', env: '配置', cfg: '配置', conf: '配置',
    json: 'JSON', xml: 'XML', toml: 'TOML',
    sql: 'SQL',
    sh: 'Shell', bash: 'Shell', zsh: 'Shell',
    js: 'JS', jsx: 'JS', mjs: 'JS', cjs: 'JS',
    ts: 'TS', tsx: 'TS',
    vue: 'Vue',
    py: 'Python', go: 'Go',
    css: 'CSS', scss: 'CSS', less: 'CSS',
    md: 'Markdown',
    pem: '密钥', key: '密钥',
};

/** 无扩展名但仍有约定的文件名 */
const FILENAME_LABELS = [
    [/^dockerfile/i, 'Dockerfile'],
    [/^(gnu)?makefile/i, 'Makefile'],
    [/^id_(rsa|dsa|ecdsa|ed25519)$/i, '密钥'],
];

/**
 * 按路径推断文件的类型展示名
 * <p>
 * 只用于界面标注，不参与任何判定；识别不出时返回 null，由调用方决定怎么显示。
 */
export function languageOf(filePath) {
    const name = String(filePath ?? '').split(/[\\/]/).pop() ?? '';
    if (!name) {
        return null;
    }
    for (const [pattern, label] of FILENAME_LABELS) {
        if (pattern.test(name)) {
            return label;
        }
    }
    const dot = name.lastIndexOf('.');
    if (dot < 0 || dot === name.length - 1) {
        return null;
    }
    return LANGUAGE_LABELS[name.slice(dot + 1).toLowerCase()] ?? null;
}
