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
