import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { hasAdminToken } from '../api/auth'
import { fetchGateStatus, hasAccessCode } from '../api/gate'

/**
 * 前端路由（Phase 16）。
 *
 * <p>用 <b>hash 模式</b>（{@link createWebHashHistory}）——hash 不发往服务端，
 * 故 SPA 导航路径与后端 API 路径（{@code /chat} {@code /admin/*} {@code /eval/run}
 * {@code /api/obs/summary}）零冲突；dev 代理与生产单 jar 静态托管皆无需 SPA fallback。
 *
 * <p>四模块：对话（Phase 16）、管理台/评测/可观测（Phase 19）。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/chat/ChatView.vue'),
    meta: { title: '对话', nav: '对话' },
  },
  {
    path: '/auth',
    name: 'auth',
    component: () => import('../views/auth/AuthView.vue'),
    meta: { title: '鉴权', nav: '' },
  },
  {
    path: '/gate',
    name: 'gate',
    component: () => import('../views/gate/GateView.vue'),
    meta: { title: '访问验证', nav: '' },
  },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('../views/admin/AdminView.vue'),
    meta: { title: '管理台', nav: '管理台' },
  },
  {
    path: '/kb',
    name: 'kb',
    component: () => import('../views/kb/KbView.vue'),
    meta: { title: '知识库', nav: '知识库' },
  },
  {
    path: '/eval',
    name: 'eval',
    component: () => import('../views/eval/EvalView.vue'),
    meta: { title: '评测', nav: '评测' },
  },
  {
    path: '/obs',
    name: 'obs',
    component: () => import('../views/observability/ObservabilityView.vue'),
    meta: { title: '可观测', nav: '可观测' },
  },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

// Phase 19 鉴权守卫（T103）：管理台/可观测台需管理令牌（X-Admin-Token），无令牌→录入页。
// 话术短路：前端不校验令牌正确性——录入后原样出站，后端 AdminAuthInterceptor 话术短路 401。
// /eval 自带 per-view 令牌输入（eval token，非 admin token），均不在此守。
// 访问闸口守卫（2026-09-18）：/chat 需访问口令（后端 Nacos 热更新开关）——闸口开启且本地无口令
// →拦到 /gate 登录页。后端不可达时不拦（降级放行，AccessGateFilter 仍在对话入口兜底）。
const ADMIN_GUARDED = new Set(['/admin', '/kb', '/obs'])
router.beforeEach(async (to) => {
  if (to.path === '/chat') {
    try {
      const status = await fetchGateStatus()
      if (status.enabled && !hasAccessCode()) {
        return { path: '/gate', query: { redirect: to.fullPath } }
      }
    } catch {
      /* 闸口状态探测失败：放行（后端闸口过滤器兜底，拒绝话术会经聊天气泡透出） */
    }
  }
  if (ADMIN_GUARDED.has(to.path) && !hasAdminToken()) {
    return { path: '/auth', query: { redirect: to.fullPath } }
  }
  return true
})

// 设置文档标题
router.afterEach((to) => {
  const title = (to.meta?.title as string | undefined) ?? ''
  document.title = title ? `Agentdemo007 · ${title}` : 'Agentdemo007'
})

export default router
