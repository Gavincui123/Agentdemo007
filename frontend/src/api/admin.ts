import { http } from './http'

/**
 * 管理台 API 模块（Phase 19·T100）。
 *
 * <p>对接后端 {@code /admin/*} 端点（T96–T98）：模型列表/权重热视图、HITL 工单列表/确认/驳回、
 * 会话历史回放。强类型接口对齐后端 record 投影（④收口：禁止 Map，字段一一映射）。
 *
 * <p>经 {@link http} 拦截器解包 {@code UnifiedResponse}——成功返回内层 data（数组/单对象），
 * 失败（如未知工单 {@code code=NOT_FOUND}）由拦截器抛 {@code ApiError}（话术 message），由视图 catch 展示。
 * 后端始终 HTTP 200（①②不暴露技术码），逻辑结果在 {@code code}。
 */

/** 模型摘要——对齐后端 {@code ModelSummary}（<b>无 apiKey</b>：密钥不外泄，T96 安全收口）。 */
export interface ModelSummary {
  id: string
  name: string
  provider: string
  endpoint: string
  weight: number
  status: 'ENABLED' | 'DISABLED'
  enabled: boolean
  tags: string[]
  maxTokens: number
  costPer1KTokens: number
}

/** HITL 工单摘要——对齐后端 {@code HitlTicketSummary}（{@code resolvedAt} 未解析为 null）。 */
export interface HitlTicketSummary {
  id: string
  sessionId: string
  query: string
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMEOUT'
  createdAt: string
  resolvedAt: string | null
}

/** 会话轮次摘要——对齐后端 {@code ChatTurnSummary}（id 为 Long→number）。 */
export interface ChatTurnSummary {
  id: number
  traceId: string
  sessionId: string
  rawInput: string
  finalReply: string
  intent: string | null
  degraded: boolean
  scenario: string | null
  timestamp: string
}

/** 模型列表与当前权重热视图：GET /admin/models。 */
export async function getModels(): Promise<ModelSummary[]> {
  return http.get('/admin/models') as unknown as Promise<ModelSummary[]>
}

/** 工单列表（全量：PENDING 优先，已决议单留痕带状态徽章·2026-09-20）：GET /admin/hitl/tickets。 */
export async function getHitlTickets(): Promise<HitlTicketSummary[]> {
  return http.get('/admin/hitl/tickets') as unknown as Promise<HitlTicketSummary[]>
}

/** 确认工单（→APPROVED）：POST /admin/hitl/tickets/{id}/confirm，返回流转后工单。 */
export async function confirmTicket(id: string): Promise<HitlTicketSummary> {
  return http.post(`/admin/hitl/tickets/${id}/confirm`) as unknown as Promise<HitlTicketSummary>
}

/** 驳回工单（→REJECTED）：POST /admin/hitl/tickets/{id}/reject，返回流转后工单。 */
export async function rejectTicket(id: string): Promise<HitlTicketSummary> {
  return http.post(`/admin/hitl/tickets/${id}/reject`) as unknown as Promise<HitlTicketSummary>
}

/** 会话历史回放：GET /admin/sessions/{sessionId}（时间升序；无轮次→空数组，②每步降级不 404）。 */
export async function getSessionHistory(sessionId: string): Promise<ChatTurnSummary[]> {
  return http.get(`/admin/sessions/${sessionId}`) as unknown as Promise<ChatTurnSummary[]>
}
