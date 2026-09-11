import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { get: vi.fn(), post: vi.fn() } }))

import { http } from './http'
import {
  getModels,
  getHitlTickets,
  confirmTicket,
  rejectTicket,
  getSessionHistory,
  type ModelSummary,
  type HitlTicketSummary,
  type ChatTurnSummary,
} from './admin'

describe('admin api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getModels GETs /admin/models and returns ModelSummary[]', async () => {
    ;(http.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 'm1',
        name: 'GPT-4',
        provider: 'openai',
        endpoint: 'https://api.openai.com',
        weight: 5,
        status: 'ENABLED',
        enabled: true,
        tags: ['chat', 'tool'],
        maxTokens: 8192,
        costPer1KTokens: 0.03,
      },
    ])
    const res = await getModels()
    expect(http.get).toHaveBeenCalledWith('/admin/models')
    expect(res).toHaveLength(1)
    expect((res[0] as ModelSummary).id).toBe('m1')
    expect((res[0] as ModelSummary).weight).toBe(5)
    // apiKey never present in the projection（密钥不外泄，T96 安全收口）
    expect('apiKey' in res[0]).toBe(false)
  })

  it('getHitlTickets GETs /admin/hitl/tickets and returns HitlTicketSummary[]', async () => {
    ;(http.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 't1',
        sessionId: 's1',
        query: '删除订单',
        reason: '高风险操作',
        status: 'PENDING',
        createdAt: '2026-09-08T10:00:00Z',
        resolvedAt: null,
      },
    ])
    const res = await getHitlTickets()
    expect(http.get).toHaveBeenCalledWith('/admin/hitl/tickets')
    expect((res[0] as HitlTicketSummary).status).toBe('PENDING')
  })

  it('confirmTicket POSTs confirm and returns resolved HitlTicketSummary', async () => {
    ;(http.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 't1',
      sessionId: 's1',
      query: '删除订单',
      reason: '高风险操作',
      status: 'APPROVED',
      createdAt: '2026-09-08T10:00:00Z',
      resolvedAt: '2026-09-08T10:05:00Z',
    })
    const res = await confirmTicket('t1')
    expect(http.post).toHaveBeenCalledWith('/admin/hitl/tickets/t1/confirm')
    expect((res as HitlTicketSummary).status).toBe('APPROVED')
  })

  it('rejectTicket POSTs reject and returns resolved HitlTicketSummary', async () => {
    ;(http.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 't2',
      sessionId: 's2',
      query: 'q',
      reason: 'r',
      status: 'REJECTED',
      createdAt: '2026-09-08T10:00:00Z',
      resolvedAt: '2026-09-08T10:06:00Z',
    })
    const res = await rejectTicket('t2')
    expect(http.post).toHaveBeenCalledWith('/admin/hitl/tickets/t2/reject')
    expect((res as HitlTicketSummary).status).toBe('REJECTED')
  })

  it('getSessionHistory GETs /admin/sessions/{id} and returns ChatTurnSummary[]', async () => {
    ;(http.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 1,
        traceId: 'tr1',
        sessionId: 's1',
        rawInput: '你好',
        finalReply: '您好',
        intent: 'CHIT_CHAT',
        degraded: false,
        scenario: null,
        timestamp: '2026-09-08T09:00:00Z',
      },
    ])
    const res = await getSessionHistory('s1')
    expect(http.get).toHaveBeenCalledWith('/admin/sessions/s1')
    expect((res[0] as ChatTurnSummary).rawInput).toBe('你好')
    expect((res[0] as ChatTurnSummary).degraded).toBe(false)
  })
})
