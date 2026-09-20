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

/** 流水线步骤实时状态（step_started/step_finished 事件驱动；P0 可观测）。 */
export interface StepView {
  name: string
  status: 'running' | 'done' | 'failed'
  outcome?: string
  scenario?: string | null
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
    /** 本轮步骤轨迹（流式轮实时更新；同步轮无事件，恒空）。 */
    steps: [] as StepView[],
    /** 当前运行中的步骤名；null=无步骤在跑（脊静态）。 */
    currentStep: null as string | null,
    /** 本轮出现过降级/短路（脊转琥珀）。 */
    spineDegraded: false,
    /** 访问闸口 401（口令缺失/失效）——ChatView 监听后跳登录页。 */
    gateRequired: false,
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
        const apiErr = e as { message?: string; code?: number }
        if (apiErr?.code === 401) this.gateRequired = true // 闸口/鉴权拒绝→登录页
        const msg = apiErr?.message ?? FALLBACK
        this.messages.push({ id: nextId(), role: 'system', content: msg, degraded: true })
      } finally {
        this.loading = false
      }
    },

    /** 流式对话：POST /chat/stream（SSE）。先建助手占位，逐步进度 + 逐帧 token 回填，终态 reply_ready 收口。signal 可中止。 */
    async sendStream(message: string, signal?: AbortSignal): Promise<void> {
      this.messages.push({ id: nextId(), role: 'user', content: message })
      const assistantId = nextId()
      this.messages.push({ id: assistantId, role: 'assistant', content: '' })
      this.streaming = true
      this.steps = []
      this.currentStep = null
      this.spineDegraded = false
      let gotTurn = false
      let pendingTraceId: string | null = null
      try {
        await streamChatTurn(message, this.sessionId, {
          signal,
          onOpen: (traceId) => {
            pendingTraceId = traceId
            this.lastTraceId = traceId
          },
          onStepStarted: (step) => {
            this.steps.push({ name: step, status: 'running' })
            this.currentStep = step
          },
          onStepFinished: (step, outcome, scenario) => {
            const entry = [...this.steps].reverse().find((s) => s.name === step && s.status === 'running')
            if (entry) {
              entry.status = outcome === 'PROCEED' ? 'done' : 'failed'
              entry.outcome = outcome
              entry.scenario = scenario
            }
            if (outcome === 'DEGRADE' || outcome === 'SHORT_CIRCUIT' || outcome === 'EXCEPTION' || scenario) {
              this.spineDegraded = true
            }
          },
          onChunk: (text) => {
            const m = this.messages.find((x) => x.id === assistantId)
            if (m) m.content += text
          },
          onTurn: (res) => {
            gotTurn = true
            if (res.sessionId) this.sessionId = res.sessionId
            const m = this.messages.find((x) => x.id === assistantId)
            if (m) {
              // 终态完整回复为权威口径：整体替换流式半截内容
              m.content = res.reply
              m.degraded = res.degraded
              m.scenario = res.scenario
              m.traceId = pendingTraceId
              m.citations = res.citations
              m.totalMs = res.totalMs ?? null
              m.firstTokenMs = res.firstTokenMs ?? null
            }
            if (res.degraded) this.spineDegraded = true
            this.currentStep = null
          },
          onError: (err, willRetry) => {
            if (willRetry) {
              // 退避重试将重开整条流：清掉半截 token 与步骤轨迹，避免跨 attempt 拼接
              const m = this.messages.find((x) => x.id === assistantId)
              if (m) m.content = ''
              this.steps = []
              this.currentStep = null
              return
            }
            if (!gotTurn) {
              const m = this.messages.find((x) => x.id === assistantId)
              if (m) {
                // 闸口/鉴权拒绝（SseRejectionError）透传后端话术；其余网络错误用通用兜底
                m.content = err?.message || FALLBACK
                m.degraded = true
              }
              const code = (err as { code?: number } | null)?.code
              if (code === 401) this.gateRequired = true // 口令缺失/失效→登录页
              this.spineDegraded = true
              this.currentStep = null
            }
          },
        })
      } catch {
        const m = this.messages.find((x) => x.id === assistantId)
        if (m && !gotTurn) {
          m.content = FALLBACK
          m.degraded = true
          this.spineDegraded = true
        }
        this.currentStep = null
      } finally {
        this.streaming = false
      }
    },

    clear(): void {
      this.messages = []
      this.sessionId = null
      this.loading = false
      this.streaming = false
      this.steps = []
      this.currentStep = null
      this.spineDegraded = false
      this.gateRequired = false
    },
  },
})
