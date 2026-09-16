import { http } from './http'
import { streamChat, type SseHandlers } from '../utils/sse'

/**
 * 对话响应数据体——与后端 {@code ChatResponse(sessionId, reply, degraded, scenario, citations, totalMs, firstTokenMs)} 同形。
 *
 * <p>{@code POST /chat} 经 {@link http} 拦截器解包 UnifiedResponse 后得本类型；
 * {@code /chat/stream} 的 SSE {@code data:} 行直接携带本类型 JSON（不经 UnifiedResponse 包裹）。
 * {@code totalMs} = 后端收到请求→终端回复就绪（毫秒）；{@code firstTokenMs} = 首个流式 token
 * 耗时（同步接口与超时兜底为 null）。
 */
export interface ChatResponse {
  sessionId: string | null
  reply: string
  degraded: boolean
  scenario: string | null
  citations: string[]
  totalMs?: number | null
  firstTokenMs?: number | null
}

/** 解析 SSE data 载荷为 {@link ChatResponse}；非 JSON 或缺 reply 返回 null。 */
export function parseChatResponse(data: string): ChatResponse | null {
  try {
    const obj = JSON.parse(data) as Record<string, unknown>
    if (typeof obj?.reply !== 'string') return null
    return {
      sessionId: typeof obj.sessionId === 'string' ? obj.sessionId : null,
      reply: obj.reply,
      degraded: typeof obj.degraded === 'boolean' ? obj.degraded : false,
      scenario: typeof obj.scenario === 'string' ? obj.scenario : null,
      citations: Array.isArray(obj.citations)
        ? obj.citations.filter((c): c is string => typeof c === 'string')
        : [],
      totalMs: typeof obj.totalMs === 'number' ? obj.totalMs : null,
      firstTokenMs: typeof obj.firstTokenMs === 'number' ? obj.firstTokenMs : null,
    }
  } catch {
    return null
  }
}

/** 同步对话：POST /chat（经拦截器解包），返回 {@link ChatResponse}。 */
export async function sendChat(message: string, sessionId?: string): Promise<ChatResponse> {
  const body: Record<string, string> = { message }
  if (sessionId) body.sessionId = sessionId
  return http.post('/chat', body) as unknown as Promise<ChatResponse>
}

export interface StreamTurnHandlers {
  onTurn: (res: ChatResponse) => void
  onOpen?: (traceId: string | null) => void
  onError?: (err: Error, willRetry: boolean) => void
  onClose?: () => void
  signal?: AbortSignal
  maxRetries?: number
}

/** 流式对话：POST /chat/stream（fetch-SSE），每个 {@link ChatResponse} 回调 onTurn。 */
export function streamChatTurn(
  message: string,
  sessionId: string | null,
  handlers: StreamTurnHandlers,
): Promise<void> {
  const body: Record<string, string> = { message }
  if (sessionId) body.sessionId = sessionId
  const sseHandlers: SseHandlers = {
    onMessage: (data) => {
      const res = parseChatResponse(data)
      if (res) handlers.onTurn(res)
    },
    onOpen: (traceId) => handlers.onOpen?.(traceId),
    onError: handlers.onError,
    onClose: handlers.onClose,
    signal: handlers.signal,
    maxRetries: handlers.maxRetries,
  }
  return streamChat('/chat/stream', body, sseHandlers)
}
