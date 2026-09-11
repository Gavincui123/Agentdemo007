import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { attachAdminToken } from './auth'

/**
 * 后端统一返回体 {@code {code,message,traceId,timestamp,data}}（第四原则·对外收口）。
 *
 * <p>后端<b>始终 HTTP 200</b>（含话术短路/降级，①②不暴露技术码），逻辑结果在 {@code code}：
 * 0=成功，非 0=逻辑错误（其 message 为面向用户的话术）。{@code traceId} 经 {@code X-Trace-Id}
 * 响应头与日志/链路对齐。
 */
export interface UnifiedResponse<T = unknown> {
  code: number
  message: string
  traceId: string
  timestamp: string
  data: T
}

const FALLBACK_MSG = '服务暂时不可用，请稍后重试'
const NETWORK_MSG = '网络连接失败，请检查后重试'

/** 统一 API 错误——携带 code/traceId，message 为面向用户的话术。 */
export class ApiError extends Error {
  code: number
  traceId: string | null
  constructor(message: string, code = 0, traceId: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

/** 从响应头大小写无关读取 traceId。 */
export function extractTraceId(headers: Record<string, unknown>): string | null {
  const key = Object.keys(headers).find((k) => k.toLowerCase() === 'x-trace-id')
  const v = key ? headers[key] : undefined
  return typeof v === 'string' && v ? v : null
}

/** 解包 UnifiedResponse：code 0 返回内层 data；非 0 抛 ApiError（message 话术，缺省兜底）。 */
export function unwrapUnified<T>(body: UnifiedResponse<T>): T {
  if (body && body.code === 0) return body.data
  throw new ApiError(body?.message || FALLBACK_MSG, body?.code ?? -1, body?.traceId ?? null)
}

/** 把 axios 错误 / 网络错误归一为 ApiError。 */
export function normalizeError(error: unknown): ApiError {
  const e = error as {
    message?: string
    response?: { status?: number; data?: UnifiedResponse }
    request?: unknown
  }
  if (e?.response?.data && typeof e.response.data.code === 'number') {
    const body = e.response.data
    return new ApiError(body.message || FALLBACK_MSG, body.code, body.traceId ?? null)
  }
  if (e?.request) return new ApiError(NETWORK_MSG, 0, null)
  return new ApiError(e?.message || FALLBACK_MSG, 0, null)
}

/**
 * axios 实例——拦截器把 UnifiedResponse 解包为内层 data（成功）或拒绝 ApiError（话术）。
 * <p>不在此处直接调 Element Plus Message——保持纯逻辑可测；由调用方/全局通知总线 catch 后展示。
 */
export const http: AxiosInstance = axios.create({ baseURL: '', timeout: 30000 })

/** 最近一次响应的 traceId（拦截器从 UnifiedResponse.traceId 或 X-Trace-Id 头捕获）。 */
export let lastTraceId: string | null = null

// 请求拦截器：注入管理台鉴权令牌（X-Admin-Token，Phase 19·T103）——纯函数 attachAdminToken，
// 有令牌才注入；无令牌原样出站，后端 AdminAuthInterceptor 话术短路 401，由路由守卫引导录入。
http.interceptors.request.use((config) =>
  attachAdminToken(config as unknown as Record<string, unknown>) as unknown as typeof config,
)

http.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data as UnifiedResponse
    lastTraceId = body?.traceId ?? extractTraceId(response.headers) ?? null
    if (body && typeof body.code === 'number') {
      return unwrapUnified(body) // 成功→内层 data；失败→抛 ApiError
    }
    return response.data // 非 UnifiedResponse 形状（直通）
  },
  (error) => Promise.reject(normalizeError(error)),
)

export default http
