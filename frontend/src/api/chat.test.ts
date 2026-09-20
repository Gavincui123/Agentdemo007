import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { post: vi.fn() } }))
vi.mock('../utils/sse', () => ({ streamChat: vi.fn() }))

import { http } from './http'
import { parseChatResponse, sendChat, streamChatTurn, type ChatResponse } from './chat'
import { streamChat } from '../utils/sse'

const streamChatMock = streamChat as unknown as ReturnType<typeof vi.fn>

describe('parseChatResponse', () => {
  it('parses valid chat response json', () => {
    const r = parseChatResponse('{"sessionId":"s1","reply":"hi","degraded":false,"scenario":null}')
    expect(r).not.toBeNull()
    expect(r as ChatResponse).toMatchObject({
      sessionId: 's1',
      reply: 'hi',
      degraded: false,
      scenario: null,
    })
  })

  it('parses degraded scenario', () => {
    const r = parseChatResponse('{"sessionId":"s1","reply":"稍后重试","degraded":true,"scenario":"MODEL_DOWN"}')
    expect((r as ChatResponse).scenario).toBe('MODEL_DOWN')
    expect((r as ChatResponse).degraded).toBe(true)
  })

  it('returns null on invalid json', () => {
    expect(parseChatResponse('not json')).toBeNull()
  })

  it('returns null when reply missing or non-string', () => {
    expect(parseChatResponse('{"sessionId":"s1"}')).toBeNull()
    expect(
      parseChatResponse('{"reply":123,"sessionId":"s1","degraded":false,"scenario":null}'),
    ).toBeNull()
  })

  it('parses citations array when present', () => {
    const r = parseChatResponse(
      '{"sessionId":"s1","reply":"hi","degraded":false,"scenario":null,"citations":["[来源: kb-refund] 退款流程"]}',
    )
    expect((r as ChatResponse).citations).toEqual(['[来源: kb-refund] 退款流程'])
  })

  it('defaults citations to empty array when absent', () => {
    const r = parseChatResponse('{"sessionId":"s1","reply":"hi","degraded":false,"scenario":null}')
    expect((r as ChatResponse).citations).toEqual([])
  })
})

describe('sendChat', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('posts to /chat with message+sessionId and returns inner data', async () => {
    ;(http.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      sessionId: 's2',
      reply: 'yo',
      degraded: false,
      scenario: null,
      citations: [],
    })
    const res = await sendChat('hello', 's2')
    expect(http.post).toHaveBeenCalledWith('/chat', { message: 'hello', sessionId: 's2' }, expect.anything())
    expect(res.reply).toBe('yo')
  })

  it('omits sessionId when not provided', async () => {
    ;(http.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      sessionId: 'gen',
      reply: 'r',
      degraded: false,
      scenario: null,
      citations: [],
    })
    await sendChat('hi')
    expect(http.post).toHaveBeenCalledWith('/chat', { message: 'hi' }, expect.anything())
  })
})

describe('streamChatTurn 事件路由（P0 可观测：命名事件分发）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /** 驱动：捕获 streamChatTurn 传给 streamChat 的 handlers，手动按事件名投递。 */
  function capture(handlers: Record<string, unknown>): void {
    streamChatMock.mockImplementation(
      async (_url: string, _body: unknown, h: Record<string, unknown>) => {
        Object.assign(handlers, h)
      },
    )
  }

  it('reply_ready → onTurn（终态载荷）', async () => {
    const h: Record<string, unknown> = {}
    capture(h)
    const onTurn = vi.fn()
    await streamChatTurn('hi', 's1', { onTurn })
    ;(h.onEvent as (n: string, d: string) => void)(
      'reply_ready',
      '{"sessionId":"s9","reply":"done","degraded":false,"scenario":null,"citations":[]}',
    )
    expect(onTurn).toHaveBeenCalledTimes(1)
    expect((onTurn.mock.calls[0]?.[0] as ChatResponse).reply).toBe('done')
    expect(streamChatMock).toHaveBeenCalledWith('/chat/stream', { message: 'hi', sessionId: 's1' }, expect.anything())
  })

  it('step_started / step_finished → onStepStarted / onStepFinished（outcome 缺省 PROCEED、scenario 缺省 null）', async () => {
    const h: Record<string, unknown> = {}
    capture(h)
    const onStepStarted = vi.fn()
    const onStepFinished = vi.fn()
    await streamChatTurn('hi', null, { onTurn: vi.fn(), onStepStarted, onStepFinished })
    ;(h.onEvent as (n: string, d: string) => void)('step_started', '{"step":"RagStep"}')
    ;(h.onEvent as (n: string, d: string) => void)('step_finished', '{"step":"RagStep"}')
    ;(h.onEvent as (n: string, d: string) => void)(
      'step_finished',
      '{"step":"RagStep","outcome":"DEGRADE","scenario":"RAG_SKIP"}',
    )
    expect(onStepStarted).toHaveBeenCalledWith('RagStep')
    expect(onStepFinished).toHaveBeenNthCalledWith(1, 'RagStep', 'PROCEED', null)
    expect(onStepFinished).toHaveBeenNthCalledWith(2, 'RagStep', 'DEGRADE', 'RAG_SKIP')
  })

  it('reply_chunk → onChunk（text 字段）', async () => {
    const h: Record<string, unknown> = {}
    capture(h)
    const onChunk = vi.fn()
    await streamChatTurn('hi', null, { onTurn: vi.fn(), onChunk })
    ;(h.onEvent as (n: string, d: string) => void)('reply_chunk', '{"text":"退款流程"}')
    expect(onChunk).toHaveBeenCalledWith('退款流程')
  })

  it('未知事件名 / 载荷形状不符 → 静默丢弃', async () => {
    const h: Record<string, unknown> = {}
    capture(h)
    const onTurn = vi.fn()
    const onChunk = vi.fn()
    const onStepStarted = vi.fn()
    await streamChatTurn('hi', null, { onTurn, onChunk, onStepStarted })
    const ev = h.onEvent as (n: string, d: string) => void
    ev('route_decided', '{"route":"RAG"}') // 后端未来新增事件
    ev('reply_chunk', 'not json')
    ev('step_started', '{"nope":1}')
    ev('reply_ready', '{"sessionId":"s1"}') // 缺 reply 字段
    expect(onTurn).not.toHaveBeenCalled()
    expect(onChunk).not.toHaveBeenCalled()
    expect(onStepStarted).not.toHaveBeenCalled()
  })
})
