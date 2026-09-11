import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { post: vi.fn() } }))

import { http } from './http'
import { runEval, type EvalReport, type StageReport, type CaseResult } from './eval'

describe('eval api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('runEval POSTs /eval/run with X-Eval-Token header and returns EvalReport', async () => {
    const report = {
      stages: [
        {
          stage: 'injection',
          passed: 2,
          total: 2,
          passRate: 1,
          cases: [{ caseId: 'inj-1', passed: true, mismatches: [] }],
        },
      ],
      totalPassed: 2,
      totalCases: 2,
    }
    ;(http.post as ReturnType<typeof vi.fn>).mockResolvedValue(report)

    const res = await runEval('dev-eval-token')

    expect(http.post).toHaveBeenCalledWith('/eval/run', undefined, {
      headers: { 'X-Eval-Token': 'dev-eval-token' },
    })
    expect((res as EvalReport).totalPassed).toBe(2)
    expect((res as EvalReport).totalCases).toBe(2)
    const stage = (res as EvalReport).stages[0] as StageReport
    expect(stage.stage).toBe('injection')
    expect(stage.passRate).toBe(1)
    // 字段名对齐后端 record：cases（非 caseResults）、caseId（非 id）
    expect(stage.cases).toHaveLength(1)
    expect((stage.cases[0] as CaseResult).caseId).toBe('inj-1')
    expect((stage.cases[0] as CaseResult).mismatches).toEqual([])
  })
})
