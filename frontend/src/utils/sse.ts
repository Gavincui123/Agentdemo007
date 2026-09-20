/**
 * SSE 解析与退避纯函数（Phase 16·TDD）。
 *
 * <p>后端 {@code /chat/stream} 为 POST（原生 EventSource 仅 GET 无法 POST），故采用
 * fetch + ReadableStream 流式读取 + 本解析器切分 {@code data:} 事件。本模块仅含
 * <b>纯函数</b>（无网络/无时钟副作用），供 {@code streamChat} 编排器与单测复用。
 *
 * <p>SSE 语义：事件以空行（{@code \n\n} / {@code \r\n\r\n}）分隔；{@code data:} 行
 * 多条按 {@code \n} 拼接；冒号后约定剥一个前导空格；{@code :} 起始为注释。
 */

/** 切分流：返回已完成事件块 + 末尾不完整残留（供下次拼接）。 */
export function splitSseStream(buffer: string): { events: string[]; remainder: string } {
  const b = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const events: string[] = []
  let pos = 0
  let idx: number
  while ((idx = b.indexOf('\n\n', pos)) !== -1) {
    const block = b.slice(pos, idx)
    if (block.length) events.push(block)
    pos = idx + 2
  }
  return { events, remainder: b.slice(pos) }
}

/** 从单个事件块抽取 data 字段（多条 data: 按 \n 拼）；无 data 返回 null。 */
export function extractSseData(event: string): string | null {
  const lines = event.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  const parts: string[] = []
  for (const line of lines) {
    if (!line || line.startsWith(':')) continue // 空行 / 注释
    const colon = line.indexOf(':')
    if (colon === -1) continue
    if (line.slice(0, colon) !== 'data') continue
    let value = line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1) // SSE 规范：剥一个前导空格
    parts.push(value)
  }
  return parts.length ? parts.join('\n') : null
}

/**
 * 从单个事件块抽取 event 字段（SSE 事件名；无 event 行返回 null，调用方按缺省 'message' 处理）。
 * P0 可观测：后端 /chat/stream 用命名事件（step_started/step_finished/reply_chunk/reply_ready），
 * 前端此前只抽 data 丢弃事件名——逐步进度与流式 token 因此全被 parse 层扔掉。
 */
export function extractSseEventName(event: string): string | null {
  const lines = event.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  for (const line of lines) {
    if (!line || line.startsWith(':')) continue // 空行 / 注释
    const colon = line.indexOf(':')
    if (colon === -1) continue
    if (line.slice(0, colon) !== 'event') continue
    let value = line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1) // SSE 规范：剥一个前导空格
    return value
  }
  return null
}

/** 指数退避（确定性，无抖动）：min(cap, base * 2^attempt)。 */
export function nextBackoff(attempt: number, base = 500, cap = 8000): number {
  return Math.min(base * Math.pow(2, attempt), cap)
}

/**
 * SSE 流被前置拒绝（闸口/鉴权）：HTTP 200 + UnifiedResponse JSON（非 event-stream）。
 * 不可重试（重试也不会变），message 为后端面向用户的话术（闸口口令/限额提示），由调用方透传气泡。
 */
export class SseRejectionError extends Error {
  code: number
  constructor(message: string, code: number) {
    super(message)
    this.name = 'SseRejectionError'
    this.code = code
  }
}

export interface SseHandlers {
  /**
   * 每个完整 SSE 事件的 data 载荷（兼容通道：不携带事件名）。
   * 未提供 {@link onEvent} 时启用；两者都提供时 onEvent 优先、onMessage 不投递。
   */
  onMessage?: (data: string) => void
  /** 命名事件通道：name=SSE event 字段（缺省 'message'），data=载荷。P0 可观测主通道。 */
  onEvent?: (name: string, data: string) => void
  /** 流成功打开（HTTP 200 + body 就绪）；携带响应头 traceId 供前端对齐日志/链路。 */
  onOpen?: (traceId: string | null) => void
  /** 错误；willRetry=true 表示将退避重试，false 表示耗尽即将关闭。 */
  onError?: (err: Error, willRetry: boolean) => void
  /** 流结束（正常关闭/重试耗尽/中止）。 */
  onClose?: () => void
  /** 中止信号；触发即停止并关闭。 */
  signal?: AbortSignal
  /** 最大重试次数（默认 3）。 */
  maxRetries?: number
  /** 附加请求头（如访问闸口 X-Access-Code），与默认头合并、同名覆盖。 */
  headers?: Record<string, string>
}

/**
 * 基于 fetch + ReadableStream 的 SSE 消费器（POST，补原生 EventSource 仅 GET 之缺）。
 *
 * <p>读流→{@link splitSseStream} 切事件→{@link extractSseData} 抽 data→onMessage。
 * HTTP 非 2xx / 网络错误 → 经 {@link nextBackoff} 退避重试至上限，耗尽 onError(willRetry=false)+onClose。
 * signal 中止即停。退避期间被中止立即唤醒关闭。
 */
export async function streamChat(
  url: string,
  body: unknown,
  handlers: SseHandlers,
): Promise<void> {
  const { onMessage, onEvent, onOpen, onError, onClose, signal, maxRetries = 3, headers } = handlers
  let attempt = 0
  for (;;) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...headers },
        body: JSON.stringify(body),
        signal,
      })
      if (signal?.aborted) {
        onClose?.()
        return
      }
      if (!res.ok || !res.body) {
        throw new Error(`SSE HTTP ${res.status}`)
      }
      // 前置拒绝（闸口/鉴权）：Content-Type 是 JSON 而非 event-stream——不可重试，话术透传
      const contentType = res.headers.get('content-type') ?? ''
      if (contentType.includes('application/json')) {
        const rejectBody = (await res.json()) as { code?: number; message?: string }
        throw new SseRejectionError(rejectBody?.message ?? '请求被拒绝', rejectBody?.code ?? 0)
      }
      onOpen?.(res.headers.get('x-trace-id'))
      await readSseStream(res.body, onMessage, onEvent, signal)
      onClose?.()
      return // 正常关闭
    } catch (err) {
      if (signal?.aborted) {
        onClose?.()
        return
      }
      if (err instanceof SseRejectionError) {
        onError?.(err, false)
        onClose?.()
        return
      }
      if (attempt >= maxRetries) {
        onError?.(err instanceof Error ? err : new Error(String(err)), false)
        onClose?.()
        return
      }
      onError?.(err instanceof Error ? err : new Error(String(err)), true)
      await delay(nextBackoff(attempt), signal)
      if (signal?.aborted) {
        onClose?.()
        return
      }
      attempt++
    }
  }
}

async function readSseStream(
  body: ReadableStream<Uint8Array>,
  onMessage: ((data: string) => void) | undefined,
  onEvent: ((name: string, data: string) => void) | undefined,
  signal?: AbortSignal,
): Promise<void> {
  // onEvent 优先（携带事件名）；仅提供 onMessage 时走兼容通道（data-only）
  const deliver = (name: string, data: string) => {
    if (onEvent) onEvent(name, data)
    else onMessage?.(data)
  }
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      if (signal?.aborted) {
        await reader.cancel().catch(() => {})
        break
      }
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { events, remainder } = splitSseStream(buffer)
      buffer = remainder
      for (const ev of events) {
        const data = extractSseData(ev)
        if (data !== null) deliver(extractSseEventName(ev) ?? 'message', data)
      }
    }
    const tail = extractSseData(buffer)
    if (tail !== null) deliver(extractSseEventName(buffer) ?? 'message', tail)
  } finally {
    reader.releaseLock()
  }
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const t = setTimeout(resolve, ms)
    if (signal) {
      signal.addEventListener(
        'abort',
        () => {
          clearTimeout(t)
          resolve()
        },
        { once: true },
      )
    }
  })
}
