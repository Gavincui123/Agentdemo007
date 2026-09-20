import { http } from './http'

/**
 * 访问闸口 API（2026-09-18 部署闸门·前端侧）。
 *
 * <p>对接后端 {@code GateController}：{@code GET /api/gate/status}（公开探测，路由守卫据此决定
 * 是否拦到登录页）+ {@code POST /api/gate/login}（口令校验，成功返回今日剩余轮次）。
 * 口令存 localStorage（对齐 admin token 模式），出站对话请求经 chat.ts 注入 {@code X-Access-Code}。
 * 闸口规则热更新在后端 Nacos（agentdemo-gate.json）——前端无感，口令轮换后旧口令自然失效（401→回登录页）。
 */

const KEY = 'agentdemo.access-code'

export interface GateStatus {
  enabled: boolean
  dailyLimit: number
}

/** 读取访问口令（localStorage 不可用时降级为 null）。 */
export function getAccessCode(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function setAccessCode(code: string): void {
  try {
    localStorage.setItem(KEY, code)
  } catch {
    /* ignore */
  }
}

export function clearAccessCode(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}

export function hasAccessCode(): boolean {
  const c = getAccessCode()
  return c !== null && c !== ''
}

let statusCache: GateStatus | null = null

/** 闸口状态（带会话内缓存——路由守卫每次 /chat 导航都会问，避免重复请求）。 */
export async function fetchGateStatus(force = false): Promise<GateStatus> {
  if (!force && statusCache) return statusCache
  statusCache = (await http.get('/api/gate/status')) as unknown as GateStatus
  return statusCache
}

/** 口令校验（后端不消耗额度）；成功返回今日剩余轮次。失败抛 ApiError（话术）。 */
export async function gateLogin(code: string): Promise<{ remaining: number }> {
  return http.post('/api/gate/login', { code }) as unknown as Promise<{ remaining: number }>
}
