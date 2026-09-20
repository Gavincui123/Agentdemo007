import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./http', () => ({ http: { get: vi.fn(), post: vi.fn() } }))

import { http } from './http'
import { startEval, fetchEvalProgress, type EvalProgress, type StageReport, type CaseResult } from './eval'

const getMock = http.get as unknown as ReturnType<typeof vi.fn>
const postMock = http.post as unknown as ReturnType<typeof vi.fn>

describe('eval api（2026-09-18 异步化：POST 秒回 + GET 进度轮询）', () => {
  beforeEach(() => vi.clearAllMocks())

  it('startEval POSTs /eval/run with X-Eval-Token header and returns EvalProgress', async () => {
    postMock.mockResolvedValue({
      runId: 'abc',
      running: false,
      totalStages: 9,
      completedStages: 9,
      currentStage: null,
      stages: [
        {
          stage: 'injection',
          passed: 2,
          total: 2,
          passRate: 1,
          cases: [{ caseId: 'inj-1', passed: true, mismatches: [] }],
          source: 'nacos',
        },
      ],
      skipped: [],
      failed: false,
      errorMessage: null,
      report: {
        stages: [
          {
            stage: 'injection',
            passed: 2,
            total: 2,
            passRate: 1,
            cases: [{ caseId: 'inj-1', passed: true, mismatches: [] }],
            source: 'nacos',
          },
        ],
        totalPassed: 2,
        totalCases: 2,
      },
      startedAtMs: 1700000000000,
    })

    const res = await startEval('dev-eval-token')

    expect(postMock).toHaveBeenCalledWith('/eval/run', undefined, {
      headers: { 'X-Eval-Token': 'dev-eval-token' },
    })
    const progress = res as EvalProgress
    expect(progress.running).toBe(false)
    expect(progress.report?.totalPassed).toBe(2)
    expect(progress.report?.totalCases).toBe(2)
    const stage = progress.report!.stages[0] as StageReport
    expect(stage.stage).toBe('injection')
    expect(stage.passRate).toBe(1)
    // 来源字段透传（Nacos 动态化：NACOS=实时拉取 / LOCAL=本地兜底）
    expect(stage.source).toBe('nacos')
    // 字段名对齐后端 record：cases（非 caseResults）、caseId（非 id）
    expect(stage.cases).toHaveLength(1)
    expect((stage.cases[0] as CaseResult).caseId).toBe('inj-1')
    expect((stage.cases[0] as CaseResult).mismatches).toEqual([])
  })

  it('startEval forwards stage filter body when provided', async () => {
    postMock.mockResolvedValue({})
    await startEval('tok', ['rag-redteam'])
    expect(postMock).toHaveBeenCalledWith('/eval/run', ['rag-redteam'], {
      headers: { 'X-Eval-Token': 'tok' },
    })
  })

  it('fetchEvalProgress GETs /eval/progress with X-Eval-Token header', async () => {
    getMock.mockResolvedValue({
      runId: 'abc123',
      running: true,
      totalStages: 9,
      completedStages: 3,
      currentStage: 'rag',
      stages: [],
      skipped: [],
      failed: false,
      errorMessage: null,
      report: null,
      startedAtMs: 1700000000000,
    })

    const res = await fetchEvalProgress('tok')

    expect(getMock).toHaveBeenCalledWith('/eval/progress', {
      headers: { 'X-Eval-Token': 'tok' },
    })
    expect((res as EvalProgress).running).toBe(true)
    expect((res as EvalProgress).currentStage).toBe('rag')
  })
})
