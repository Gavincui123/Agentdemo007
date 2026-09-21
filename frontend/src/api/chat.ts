import { http } from './http'
import { getAccessCode } from './gate'
import { identityUserId } from '../stores/identity'
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

/** 访问闸口口令头（闸口开启时必带；未存口令不带，后端 401 话术引导回登录页）。 */
function accessHeaders(): Record<string, string> {
  const code = getAccessCode()
  return code ? { 'X-Access-Code': code } : {}
}

/** 对话请求公共体：message + sessionId（可选）+ 演示身份 userId（游客不传=匿名 V0 口径）。 */
function chatBody(message: string, sessionId?: string | null): Record<string, string> {
  const body: Record<string, string> = { message }
  if (sessionId) body.sessionId = sessionId
  const uid = identityUserId()
  if (uid) body.userId = uid
  return body
}

/** 同步对话：POST /chat（经拦截器解包），返回 {@link ChatResponse}。 */
export async function sendChat(message: string, sessionId?: string): Promise<ChatResponse> {
  return http.post('/chat', chatBody(message, sessionId), { headers: accessHeaders() }) as unknown as Promise<ChatResponse>
}

export interface StreamTurnHandlers {
  onTurn: (res: ChatResponse) => void
  /** step_started 事件：步骤开始（step=PipelineStep.name，如 "RagStep"）。 */
  onStepStarted?: (step: string) => void
  /** step_finished 事件：outcome ∈ PROCEED/SHORT_CIRCUIT/DEGRADE/RETRY/EXCEPTION；scenario 仅降级非空。 */
  onStepFinished?: (step: string, outcome: string, scenario: string | null) => void
  /** reply_chunk 事件：流式部分文本块（逐段追加渲染）。 */
  onChunk?: (text: string) => void
  onOpen?: (traceId: string | null) => void
  onError?: (err: Error, willRetry: boolean) => void
  onClose?: () => void
  signal?: AbortSignal
  maxRetries?: number
}

/** 从事件 JSON 抽字符串字段；非 JSON 或非字符串 → null。 */
function parseStringField(data: string, field: string): string | null {
  try {
    const obj = JSON.parse(data) as Record<string, unknown>
    return typeof obj?.[field] === 'string' ? (obj[field] as string) : null
  } catch {
    return null
  }
}

/**
 * 流式对话：POST /chat/stream（fetch-SSE），按事件名分发——
 * {@code reply_ready}→onTurn（终态，与 /chat 同形）；{@code step_started}/{@code step_finished}→
 * 逐步进度；{@code reply_chunk}→流式 token。未知事件名/载荷形状不符→静默丢弃（进度 best-effort）。
 */
export function streamChatTurn(
  message: string,
  sessionId: string | null,
  handlers: StreamTurnHandlers,
): Promise<void> {
  const body = chatBody(message, sessionId)
  const sseHandlers: SseHandlers = {
    headers: accessHeaders(),
    onEvent: (name, data) => {
      if (name === 'reply_ready') {
        const res = parseChatResponse(data)
        if (res) handlers.onTurn(res)
        return
      }
      if (name === 'step_started') {
        const step = parseStringField(data, 'step')
        if (step) handlers.onStepStarted?.(step)
        return
      }
      if (name === 'step_finished') {
        const step = parseStringField(data, 'step')
        if (!step) return
        const outcome = parseStringField(data, 'outcome') ?? 'PROCEED'
        const scenario = parseStringField(data, 'scenario')
        handlers.onStepFinished?.(step, outcome, scenario)
        return
      }
      if (name === 'reply_chunk') {
        const text = parseStringField(data, 'text')
        if (text) handlers.onChunk?.(text)
      }
    },
    onOpen: (traceId) => handlers.onOpen?.(traceId),
    onError: handlers.onError,
    onClose: handlers.onClose,
    signal: handlers.signal,
    maxRetries: handlers.maxRetries,
  }
  return streamChat('/chat/stream', body, sseHandlers)
}
