import { http } from './http'

/**
 * 评测 API 模块（Phase 19·T101）。
 *
 * <p>对接后端 {@code POST /eval/run}（T70）：批量执行黄金数据集评测，返回 {@link EvalReport}
 * （各 stage 通过率 + per-case 结果）。强类型接口对齐后端 record 投影（④收口）。
 *
 * <p>鉴权：后端 {@code EvalAuthenticator} 取 {@code X-Eval-Token} 请求头逐字比对
 * （dev 默认 {@code dev-eval-token}，机制强制鉴权——无 token 即拒，code=401 不返回报告）。
 * token 由视图输入；未授权 → 拦截器抛 {@code ApiError}（code=401 话术），视图展示。
 */

/** 单例结果——对齐后端 {@code CaseResult}（{@code caseId} 非 id）。 */
export interface CaseResult {
  caseId: string
  passed: boolean
  mismatches: string[]
}

/** 单 stage 报告——对齐后端 {@code StageReport}（{@code cases} 非 caseResults；passRate 0.0–1.0）。 */
export interface StageReport {
  stage: string
  passed: number
  total: number
  passRate: number
  cases: CaseResult[]
  /** 黄金数据来源：nacos=本次实时拉取的 Nacos 数据（改数据不重启）；local=本地 classpath 兜底。 */
  source?: 'nacos' | 'local'
}

/** 评测总报告——对齐后端 {@code EvalReport}（③环节测评·强类型收口）。 */
export interface EvalReport {
  stages: StageReport[]
  totalPassed: number
  totalCases: number
}

/**
 * 评测进度快照——对齐后端 {@code EvalProgress}（2026-09-18 评测异步化）。
 *
 * <p>POST /eval/run 秒回启动作业（不再同步长请求——全量真 LLM 调用曾拖爆 axios 30s 超时），
 * GET /eval/progress 每秒轮询：运行中即部分可见 {@code stages}，完成后 {@code report} 聚合全量。
 */
export interface EvalProgress {
  runId: string | null
  running: boolean
  totalStages: number
  completedStages: number
  currentStage: string | null
  stages: StageReport[]
  skipped: string[]
  failed: boolean
  errorMessage: string | null
  report: EvalReport | null
  startedAtMs: number
}

/**
 * 启动评测作业：POST /eval/run（携带 {@code X-Eval-Token} 头），秒回当前进度快照。
 * 已有作业在跑 → code=409 话术（ApiError）。
 * @param token 评测令牌（须与后端 {@code agentdemo.eval.token} 一致；dev 默认 {@code dev-eval-token}，Nacos/EVAL_TOKEN 覆盖时以实际为准）
 */
export async function startEval(token: string, stages?: string[]): Promise<EvalProgress> {
  return http.post('/eval/run', stages ?? undefined, {
    headers: { 'X-Eval-Token': token },
  }) as unknown as Promise<EvalProgress>
}

/** 轮询评测进度：GET /eval/progress（携带 {@code X-Eval-Token} 头）。 */
export async function fetchEvalProgress(token: string): Promise<EvalProgress> {
  return http.get('/eval/progress', {
    headers: { 'X-Eval-Token': token },
  }) as unknown as Promise<EvalProgress>
}
