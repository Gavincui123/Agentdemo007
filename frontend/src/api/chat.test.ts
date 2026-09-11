import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { post: vi.fn() } }))

import { http } from './http'
import { parseChatResponse, sendChat, type ChatResponse } from './chat'

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
    expect(http.post).toHaveBeenCalledWith('/chat', { message: 'hello', sessionId: 's2' })
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
    expect(http.post).toHaveBeenCalledWith('/chat', { message: 'hi' })
  })
})
