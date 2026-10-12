import { createRouter, createWebHistory } from 'vue-router';

import OverviewView from '../views/OverviewView.vue';
import IssuesView from '../views/IssuesView.vue';
import IssueDetailView from '../views/IssueDetailView.vue';
import ReportView from '../views/ReportView.vue';
import RulesView from '../views/RulesView.vue';
import LlmKeysView from '../views/LlmKeysView.vue';

/**
 * 用 history 模式而非 hash：URL 干净、可直接深链到某个问题。
 * 代价是刷新 /issues 这类路径需要后端把未知路径 forward 到 index.html —— 见
 * cq-web 的 SpaWebConfig（只放行不含扩展名且不以 /api、/test 开头的路径）。
 */
const routes = [
    { path: '/', name: 'overview', component: OverviewView, meta: { title: '概览', tab: 'overview' } },
    { path: '/issues', name: 'issues', component: IssuesView, meta: { title: '问题列表', tab: 'issues' } },
    {
        path: '/issues/:id',
        name: 'issue-detail',
        component: IssueDetailView,
        meta: { title: '问题详情', tab: 'issues' },
    },
    { path: '/report', name: 'report', component: ReportView, meta: { title: '报告与趋势', tab: 'report' } },
    { path: '/rules', name: 'rules', component: RulesView, meta: { title: '规则配置', tab: 'rules' } },
    { path: '/llm', name: 'llm', component: LlmKeysView, meta: { title: '大模型配置', tab: 'llm' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
    scrollBehavior: () => ({ top: 0 }),
});

export default router;
