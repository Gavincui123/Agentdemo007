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

describe('useChatStore.sendStream P0 可观测（步骤进度 + 流式 token）', () => {
  it('step_started/finished 维护 steps 轨迹与 currentStep；PROCEED 记 done', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onStepStarted?.('RagStep')
      handlers.onStepFinished?.('RagStep', 'PROCEED', null)
      handlers.onTurn({ sessionId: 's1', reply: 'r', degraded: false, scenario: null })
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    expect(store.steps).toEqual([{ name: 'RagStep', status: 'done', outcome: 'PROCEED', scenario: null }])
    expect(store.currentStep).toBeNull() // 终态后脊回到静态
    expect(store.spineDegraded).toBe(false)
  })

  it('onChunk 逐段追加到助手占位；reply_ready 整体替换为权威回复', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onChunk?.('退款')
      handlers.onChunk?.('流程如下')
      handlers.onTurn({ sessionId: 's1', reply: '退款流程如下：…', degraded: false, scenario: null })
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('退款')
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant?.content).toBe('退款流程如下：…')
  })

  it('step_finished DEGRADE/SHORT_CIRCUIT 或带 scenario → spineDegraded=true', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onStepStarted?.('RagStep')
      handlers.onStepFinished?.('RagStep', 'SHORT_CIRCUIT', 'RAG_SKIP')
      handlers.onTurn({ sessionId: 's1', reply: '兜底', degraded: true, scenario: 'RAG_SKIP' })
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    expect(store.spineDegraded).toBe(true)
    expect(store.steps[0]).toMatchObject({ status: 'failed', outcome: 'SHORT_CIRCUIT', scenario: 'RAG_SKIP' })
  })

  it('onError willRetry=true 清空半截 token 与步骤轨迹（重开流不拼接）', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onStepStarted?.('RagStep')
      handlers.onChunk?.('半截')
      handlers.onError?.(new Error('boom'), true) // 将重试
      handlers.onStepStarted?.('RagStep') // 新 attempt 重新累积
      handlers.onStepFinished?.('RagStep', 'PROCEED', null)
      handlers.onTurn({ sessionId: 's1', reply: '完整回复', degraded: false, scenario: null })
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant?.content).toBe('完整回复') // 无跨 attempt 拼接
    expect(store.steps).toHaveLength(1) // 旧轨迹被重试重置
    expect(store.spineDegraded).toBe(false)
  })

  it('新一轮 sendStream 重置上一轮的 steps/spineDegraded', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onStepStarted?.('RagStep')
      handlers.onStepFinished?.('RagStep', 'DEGRADE', 'RAG_SKIP')
      handlers.onTurn({ sessionId: 's1', reply: 'r', degraded: true, scenario: 'RAG_SKIP' })
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('第一轮')
    expect(store.spineDegraded).toBe(true)
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onTurn({ sessionId: 's1', reply: 'r2', degraded: false, scenario: null })
      handlers.onClose?.()
    })
    await store.sendStream('第二轮')
    expect(store.steps).toEqual([])
    expect(store.spineDegraded).toBe(false)
  })

  it('clear() 一并清空步骤状态', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      handlers.onStepStarted?.('OutputStep')
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    expect(store.steps).toHaveLength(1)
    store.clear()
    expect(store.steps).toEqual([])
    expect(store.currentStep).toBeNull()
    expect(store.spineDegraded).toBe(false)
  })
})

describe('useChatStore 访问闸口（2026-09-18 部署闸门）', () => {
  it('流式闸口拒绝：后端话术进气泡，401 置 gateRequired', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      const err = Object.assign(new Error('本站为受限体验，请先输入访问口令'), { code: 401 })
      handlers.onError?.(err, false)
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant?.content).toBe('本站为受限体验，请先输入访问口令')
    expect(assistant?.degraded).toBe(true)
    expect(store.gateRequired).toBe(true)
  })

  it('流式限额拒绝（429）：话术进气泡，不置 gateRequired（不跳登录页）', async () => {
    streamMock.mockImplementation(async (_msg, _sid, handlers) => {
      const err = Object.assign(new Error('今日体验轮次已用完，欢迎明天再来'), { code: 429 })
      handlers.onError?.(err, false)
      handlers.onClose?.()
    })
    const store = useChatStore()
    await store.sendStream('hi')
    const assistant = store.messages.find((m) => m.role === 'assistant')
    expect(assistant?.content).toBe('今日体验轮次已用完，欢迎明天再来')
    expect(store.gateRequired).toBe(false)
  })

  it('同步闸口拒绝：ApiError 话术进气泡，401 置 gateRequired', async () => {
    sendChatMock.mockRejectedValue(Object.assign(new ApiError('请先输入访问口令', 401, 't1')))
    const store = useChatStore()
    await store.send('hi')
    const sys = store.messages.find((m) => m.role === 'system')
    expect(sys?.content).toBe('请先输入访问口令')
    expect(store.gateRequired).toBe(true)
  })
})
