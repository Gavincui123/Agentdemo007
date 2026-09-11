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

/** 指数退避（确定性，无抖动）：min(cap, base * 2^attempt)。 */
export function nextBackoff(attempt: number, base = 500, cap = 8000): number {
  return Math.min(base * Math.pow(2, attempt), cap)
}

export interface SseHandlers {
  /** 每个完整 SSE 事件的 data 载荷。 */
  onMessage: (data: string) => void
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
  const { onMessage, onOpen, onError, onClose, signal, maxRetries = 3 } = handlers
  let attempt = 0
  for (;;) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
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
      onOpen?.(res.headers.get('x-trace-id'))
      await readSseStream(res.body, onMessage, signal)
      onClose?.()
      return // 正常关闭
    } catch (err) {
      if (signal?.aborted) {
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
  onMessage: (data: string) => void,
  signal?: AbortSignal,
): Promise<void> {
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
        if (data !== null) onMessage(data)
      }
    }
    const tail = extractSseData(buffer)
    if (tail !== null) onMessage(tail)
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
