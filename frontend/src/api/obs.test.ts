import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { get: vi.fn() } }))

import { http } from './http'
import { getObservabilitySummary, type ObservabilitySummary } from './obs'

describe('obs api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getObservabilitySummary GETs /api/obs/summary and returns ObservabilitySummary', async () => {
    ;(http.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      chatRequests: 128,
      degradationTotal: 7,
      degradationByScenario: { RAG_SKIP: 4, TOOL_FAILURE: 2, SHORT_CIRCUIT: 1 },
      outcomeOk: 100,
      outcomeDegraded: 7,
      outcomeShortCircuit: 21,
      ragHit: 60,
      ragMiss: 12,
      toolFailure: 9,
      hitlTriggered: 3,
      failoverExhausted: 2,
      modelCount: 4,
      modelEnabled: 3,
      hitlPendingTickets: 1,
      totalTurns: 128,
      degradedTurns: 7,
    })

    const res = await getObservabilitySummary()

    expect(http.get).toHaveBeenCalledWith('/api/obs/summary')
    // 16 字段一一映射后端 record（④收口：禁 Map 除外泄桶 degradationByScenario）
    const s = res as ObservabilitySummary
    expect(s.chatRequests).toBe(128)
    expect(s.degradationTotal).toBe(7)
    expect(s.degradationByScenario).toEqual({
      RAG_SKIP: 4,
      TOOL_FAILURE: 2,
      SHORT_CIRCUIT: 1,
    })
    expect(s.outcomeOk).toBe(100)
    expect(s.outcomeDegraded).toBe(7)
    expect(s.outcomeShortCircuit).toBe(21)
    expect(s.ragHit).toBe(60)
    expect(s.ragMiss).toBe(12)
    expect(s.toolFailure).toBe(9)
    expect(s.hitlTriggered).toBe(3)
    expect(s.failoverExhausted).toBe(2)
    expect(s.modelCount).toBe(4)
    expect(s.modelEnabled).toBe(3)
    expect(s.hitlPendingTickets).toBe(1)
    expect(s.totalTurns).toBe(128)
    expect(s.degradedTurns).toBe(7)
  })

  it('empty-state summary: all zero + empty map (②每步降级不报错)', async () => {
    ;(http.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      chatRequests: 0,
      degradationTotal: 0,
      degradationByScenario: {},
      outcomeOk: 0,
      outcomeDegraded: 0,
      outcomeShortCircuit: 0,
      ragHit: 0,
      ragMiss: 0,
      toolFailure: 0,
      hitlTriggered: 0,
      failoverExhausted: 0,
      modelCount: 0,
      modelEnabled: 0,
      hitlPendingTickets: 0,
      totalTurns: 0,
      degradedTurns: 0,
    })

    const res = await getObservabilitySummary()
    const s = res as ObservabilitySummary
    expect(s.chatRequests).toBe(0)
    expect(s.degradationTotal).toBe(0)
    expect(s.degradationByScenario).toEqual({})
    expect(s.totalTurns).toBe(0)
  })
})
