import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/chat', () => ({ sendChat: vi.fn(), streamChatTurn: vi.fn() }))

import { useChatStore } from './chat'
import { sendChat, streamChatTurn } from '../api/chat'
import { ApiError } from '../api/http'

const sendChatMock = sendChat as unknown as ReturnType<typeof vi.fn>
const streamMock = streamChatTurn as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('useChatStore.send', () => {
  it('adds user + assistant messages, stores sessionId, clears loading', async () => {
    sendChatMock.mockResolvedValue({
      sessionId: 's1',
      reply: 'hello',
      degraded: false,
      scenario: null,
    })
    const store = useChatStore()
    await store.send('hi')
    expect(store.messages).toHaveLength(2)
    expect(store.messages[0]).toMatchObject({ role: 'user', content: 'hi' })
    expect(store.messages[1]).toMatchObject({ role: 'assistant', content: 'hello', degraded: false })
    expect(store.sessionId).toBe('s1')
    expect(store.loading).toBe(false)
  })

  it('on api error pushes degradation system message and clears loading', async () => {
    sendChatMock.mockRejectedValue(new ApiError('降级话术', 400, 't1'))
    const store = useChatStore()
    await store.send('hi')
    expect(store.loading).toBe(false)
    const sys = store.messages.find((m) => m.role === 'system')
    expect(sys).toBeTruthy()
    expect(sys?.degraded).toBe(true)
    expect(sys?.content).toBe('降级话术')
  })

  it('reuses existing sessionId for multi-turn context', async () => {
    sendChatMock.mockResolvedValue({
      sessionId: 's1',
      reply: 'r1',
      degraded: false,
      scenario: null,
    })
    const store = useChatStore()
    store.sessionId = 's1'
    await store.send('second')
    expect(sendChatMock).toHaveBeenCalledWith('second', 's1')
  })
})

describe('useChatStore.sendStream', () => {
  it('appends assistant turn via stream and clears streaming on close', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onTurn({ sessionId: 's1', reply: 'streamed', degraded: false, scenario: null })
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant?.content).toBe('streamed')
    expect(store.streaming).toBe(false)
    expect(store.sessionId).toBe('s1')
  })

  it('fills a degradation note on stream error with no turn', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onError?.(new Error('boom'), false)
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    expect(store.streaming).toBe(false)
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant?.degraded).toBe(true)
  })

  it('threads an abort signal through to the stream', async () => {
    const ctrl = new AbortController()
    const captured: { handlers: { signal?: AbortSignal } | null } = { handlers: null }
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      captured.handlers = handlers
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi', ctrl.signal)
    expect(captured.handlers?.signal).toBe(ctrl.signal)
  })
})
