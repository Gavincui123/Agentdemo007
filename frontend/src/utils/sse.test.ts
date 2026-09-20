import { describe, it, expect, vi } from 'vitest'
import { splitSseStream, extractSseData, extractSseEventName, nextBackoff, streamChat } from './sse'

describe('splitSseStream', () => {
  it('returns one complete event and empty remainder', () => {
    const r = splitSseStream('data:hello\n\n')
    expect(r.events).toEqual(['data:hello'])
    expect(r.remainder).toBe('')
  })

  it('returns no events when buffer has no terminator', () => {
    const r = splitSseStream('data:hello')
    expect(r.events).toEqual([])
    expect(r.remainder).toBe('data:hello')
  })

  it('splits two consecutive events', () => {
    const r = splitSseStream('data:a\n\ndata:b\n\n')
    expect(r.events).toEqual(['data:a', 'data:b'])
    expect(r.remainder).toBe('')
  })

  it('keeps trailing partial as remainder after a complete event', () => {
    const r = splitSseStream('data:a\n\ndata:b')
    expect(r.events).toEqual(['data:a'])
    expect(r.remainder).toBe('data:b')
  })

  it('normalizes CRLF line endings to LF before splitting', () => {
    const r = splitSseStream('data:a\r\n\r\ndata:b\r\n\r\n')
    expect(r.events).toEqual(['data:a', 'data:b'])
    expect(r.remainder).toBe('')
  })
})

describe('extractSseData', () => {
  it('extracts single data line payload', () => {
    expect(extractSseData('data:{"reply":"hi"}')).toBe('{"reply":"hi"}')
  })

  it('joins multiple data lines with newline', () => {
    expect(extractSseData('data:line1\ndata:line2')).toBe('line1\nline2')
  })

  it('strips exactly one leading space after the colon per SSE spec', () => {
    expect(extractSseData('data: spaced')).toBe('spaced')
    // two spaces: only one stripped, second preserved
    expect(extractSseData('data:  two')).toBe(' two')
  })

  it('ignores comment lines and returns null when no data', () => {
    expect(extractSseData(':ping\n')).toBeNull()
  })

  it('ignores non-data fields (event/id/retry)', () => {
    expect(extractSseData('event:token\ndata:payload')).toBe('payload')
  })
})

describe('nextBackoff', () => {
  it('doubles from base each attempt', () => {
    expect(nextBackoff(0)).toBe(500)
    expect(nextBackoff(1)).toBe(1000)
    expect(nextBackoff(2)).toBe(2000)
  })

  it('caps at the configured maximum', () => {
    expect(nextBackoff(5)).toBe(8000) // 500*2^5=16000 → cap 8000
    expect(nextBackoff(20)).toBe(8000)
  })

  it('respects custom base and cap', () => {
    expect(nextBackoff(0, 100, 400)).toBe(100)
    expect(nextBackoff(3, 100, 400)).toBe(400) // 800 → cap 400
  })
})

function mockSseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c))
      controller.close()
    },
  })
  return new Response(stream, {
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
  })
}

describe('streamChat', () => {
  const url = '/chat/stream'
  const body = { message: 'hi', sessionId: 's1' }

  it('streams data events to onMessage then closes', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockSseResponse(['data:hello\n\n'])))
    const onMessage = vi.fn()
    const onOpen = vi.fn()
    const onClose = vi.fn()
    await streamChat(url, body, { onMessage, onOpen, onClose })
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(onMessage).toHaveBeenCalledWith('hello')
    expect(onClose).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })

  it('reassembles split chunks across reads', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => mockSseResponse(['data:hel', 'lo\n\n', 'data:world\n\n'])),
    )
    const onMessage = vi.fn()
    await streamChat(url, body, { onMessage })
    expect(onMessage).toHaveBeenNthCalledWith(1, 'hello')
    expect(onMessage).toHaveBeenNthCalledWith(2, 'world')
    vi.unstubAllGlobals()
  })

  it('reports error and closes when http fails with no retries', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 500 })))
    const onError = vi.fn()
    const onClose = vi.fn()
    await streamChat(url, body, { onMessage: vi.fn(), onError, onClose, maxRetries: 0 })
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0]?.[1]).toBe(false) // willRetry=false
    expect(onClose).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })

  it('retries once then closes when first attempt errors', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 500 }))
      .mockResolvedValueOnce(mockSseResponse(['data:ok\n\n']))
    vi.stubGlobal('fetch', fetchMock)
    const onMessage = vi.fn()
    const onError = vi.fn()
    await streamChat(url, body, { onMessage, onError, maxRetries: 2 })
    expect(fetchMock).toHaveBeenCalledTimes(2) // retried
    expect(onError).toHaveBeenCalledTimes(1) // first failure reported
    expect(onMessage).toHaveBeenCalledWith('ok')
    vi.unstubAllGlobals()
  })

  it('stops when signal aborts', async () => {
    const ac = new AbortController()
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        ac.abort()
        return mockSseResponse(['data:hello\n\n'])
      }),
    )
    const onClose = vi.fn()
    await streamChat(url, body, { onMessage: vi.fn(), onClose, signal: ac.signal })
    expect(onClose).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })
})

describe('extractSseEventName', () => {
  it('extracts the event field name', () => {
    expect(extractSseEventName('event:step_started\ndata:{"step":"RagStep"}')).toBe('step_started')
  })

  it('returns null when no event line present (default message per SSE spec)', () => {
    expect(extractSseEventName('data:payload')).toBeNull()
  })

  it('strips one leading space after the colon', () => {
    expect(extractSseEventName('event: reply_ready\ndata:{}')).toBe('reply_ready')
  })

  it('ignores comment lines and data lines', () => {
    expect(extractSseEventName(':ping\ndata:x')).toBeNull()
  })
})

describe('streamChat onEvent (P0 可观测：命名事件通道)', () => {
  const url = '/chat/stream'

  it('delivers named events with their SSE name to onEvent', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        mockSseResponse([
          'event:step_started\ndata:{"step":"RagStep"}\n\n',
          'event:reply_chunk\ndata:{"text":"你"}\n\n',
        ]),
      ),
    )
    const onEvent = vi.fn()
    await streamChat(url, {}, { onEvent })
    expect(onEvent).toHaveBeenNthCalledWith(1, 'step_started', '{"step":"RagStep"}')
    expect(onEvent).toHaveBeenNthCalledWith(2, 'reply_chunk', '{"text":"你"}')
    vi.unstubAllGlobals()
  })

  it('defaults unnamed events to "message"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockSseResponse(['data:bare\n\n'])))
    const onEvent = vi.fn()
    await streamChat(url, {}, { onEvent })
    expect(onEvent).toHaveBeenCalledWith('message', 'bare')
    vi.unstubAllGlobals()
  })

  it('onEvent takes priority: onMessage is not called when both provided', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockSseResponse(['event:e\ndata:x\n\n'])))
    const onEvent = vi.fn()
    const onMessage = vi.fn()
    await streamChat(url, {}, { onEvent, onMessage })
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onMessage).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('falls back to onMessage when onEvent absent (legacy channel intact)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => mockSseResponse(['event:e\ndata:x\n\n'])))
    const onMessage = vi.fn()
    await streamChat(url, {}, { onMessage })
    expect(onMessage).toHaveBeenCalledWith('x')
    vi.unstubAllGlobals()
  })
})

describe('streamChat 前置拒绝（P0 闸口：JSON 拒绝体识别）', () => {
  const url = '/chat/stream'

  function mockJsonResponse(body: Record<string, unknown>): Response {
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    })
  }

  it('json content-type → SseRejectionError（话术+code），不重试不进 SSE 解析', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        mockJsonResponse({ code: 429, message: '今日体验轮次已用完，欢迎明天再来' }),
      ),
    )
    const onError = vi.fn()
    const onOpen = vi.fn()
    const onEvent = vi.fn()
    const onClose = vi.fn()
    await streamChat(url, {}, { onEvent, onOpen, onError, onClose })
    expect(fetchMockCalls()).toBe(1) // 不可重试：只请求一次
    expect(onOpen).not.toHaveBeenCalled() // 未进 SSE 流
    expect(onEvent).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledTimes(1)
    const err = onError.mock.calls[0]?.[0] as { code: number; message: string }
    expect(onError.mock.calls[0]?.[1]).toBe(false) // willRetry=false
    expect(err.code).toBe(429)
    expect(err.message).toBe('今日体验轮次已用完，欢迎明天再来')
    expect(onClose).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })

  function fetchMockCalls(): number {
    return (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length
  }
})
