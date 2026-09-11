/**
 * 管理台鉴权令牌存储（Phase 19·T103 前端侧）。
 *
 * <p>对应后端 {@code AdminAuthInterceptor}：管理台/可观测端点（{@code /admin/**} {@code /api/obs/**}）
 * 需 {@code X-Admin-Token} 头逐字比对 {@code agentdemo.admin.token}（dev 默认 {@code dev-admin-token}）。
 * 令牌存 localStorage，由请求拦截器（{@link attachAdminToken}）注入到每个出站请求头。
 *
 * <p>对应后端 ①话术短路：无令牌→后端返 {@code code=401}（HTTP 200），经 {@link http} 拦截器抛
 * {@code ApiError}（话术「未授权」），由路由守卫拦截至令牌录入页，不暴露技术码。
 */
const KEY = 'agentdemo.admin-token'

/** 读取令牌（localStorage 不可用时降级为 null，②每步降级不抛）。 */
export function getAdminToken(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

/** 存储令牌（不可用时静默忽略）。 */
export function setAdminToken(token: string): void {
  try {
    localStorage.setItem(KEY, token)
  } catch {
    /* ignore */
  }
}

/** 是否已持令牌（空串视为无）。 */
export function hasAdminToken(): boolean {
  const t = getAdminToken()
  return t !== null && t !== ''
}

/** 清除令牌（登出/换号）。 */
export function clearAdminToken(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}

/**
 * 请求拦截器纯函数：有令牌则注入 {@code X-Admin-Token} 头（不改原 config）。
 *
 * <p>由 {@link http} 请求拦截器调用——纯函数化以可单测（与 {@code unwrapUnified} 同构）。
 * 无令牌→原样返回（后端将短路 401，前端路由守卫引导录入）。
 */
export function attachAdminToken(config: Record<string, unknown>): Record<string, unknown> {
  const token = getAdminToken()
  if (!token) return config
  const headers = (config.headers as Record<string, string> | undefined) ?? {}
  return { ...config, headers: { ...headers, 'X-Admin-Token': token } }
}
