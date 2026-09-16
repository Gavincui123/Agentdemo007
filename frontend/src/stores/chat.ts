import { defineStore } from 'pinia'
import { sendChat, streamChatTurn, type ChatResponse } from '../api/chat'
import { lastTraceId } from '../api/http'

/**
 * 对话会话状态（Phase 16）。
 *
 * <p>消息列表 + sessionId（多轮上下文）+ loading/streaming。降级/话术短路时回退为
 * system 消息（degraded 标记）——前端镜像「系统仍答」语义（①②），不抛错给用户。
 */
export type Role = 'user' | 'assistant' | 'system'

export interface ChatMessage {
  id: string
  role: Role
  content: string
  degraded?: boolean
  scenario?: string | null
  traceId?: string | null
  citations?: string[]
  /** 后端计时（毫秒）：本轮总耗时 / 首个流式 token 耗时（同步接口为 null）。 */
  totalMs?: number | null
  firstTokenMs?: number | null
}

const FALLBACK = '服务暂时不可用，请稍后重试'

let seq = 0
const nextId = (): string => `m${seq++}`

export const useChatStore = defineStore('chat', {
  state: () => ({
    messages: [] as ChatMessage[],
    sessionId: null as string | null,
    loading: false,
    streaming: false,
    lastTraceId: null as string | null,
  }),
  actions: {
    /** 同步对话：POST /chat。 */
    async send(message: string): Promise<void> {
      this.messages.push({ id: nextId(), role: 'user', content: message })
      this.loading = true
      try {
        const res: ChatResponse = await sendChat(message, this.sessionId ?? undefined)
        if (res.sessionId) this.sessionId = res.sessionId
        this.lastTraceId = lastTraceId
        this.messages.push({
          id: nextId(),
          role: 'assistant',
          content: res.reply,
          degraded: res.degraded,
          scenario: res.scenario,
          traceId: lastTraceId,
          citations: res.citations,
          totalMs: res.totalMs ?? null,
          firstTokenMs: res.firstTokenMs ?? null,
        })
      } catch (e) {
        const msg = (e as { message?: string })?.message ?? FALLBACK
        this.messages.push({ id: nextId(), role: 'system', content: msg, degraded: true })
      } finally {
        this.loading = false
      }
    },

    /** 流式对话：POST /chat/stream（SSE）。先建助手占位，逐帧回填。signal 可中止。 */
    async sendStream(message: string, signal?: AbortSignal): Promise<void> {
      this.messages.push({ id: nextId(), role: 'user', content: message })
      const assistantId = nextId()
      this.messages.push({ id: assistantId, role: 'assistant', content: '' })
      this.streaming = true
      let gotTurn = false
      let pendingTraceId: string | null = null
      try {
        await streamChatTurn(message, this.sessionId, {
          signal,
          onOpen: (traceId) => {
            pendingTraceId = traceId
            this.lastTraceId = traceId
          },
          onTurn: (res) => {
            gotTurn = true
            if (res.sessionId) this.sessionId = res.sessionId
            const m = this.messages.find((x) => x.id === assistantId)
            if (m) {
              m.content = res.reply
              m.degraded = res.degraded
              m.scenario = res.scenario
              m.traceId = pendingTraceId
              m.citations = res.citations
              m.totalMs = res.totalMs ?? null
              m.firstTokenMs = res.firstTokenMs ?? null
            }
          },
          onError: () => {
            if (!gotTurn) {
              const m = this.messages.find((x) => x.id === assistantId)
              if (m) {
                m.content = FALLBACK
                m.degraded = true
              }
            }
          },
        })
      } catch {
        const m = this.messages.find((x) => x.id === assistantId)
        if (m && !gotTurn) {
          m.content = FALLBACK
          m.degraded = true
        }
      } finally {
        this.streaming = false
      }
    },

    clear(): void {
      this.messages = []
      this.sessionId = null
      this.loading = false
      this.streaming = false
    },
  },
})
