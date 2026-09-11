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
}

/** 评测总报告——对齐后端 {@code EvalReport}（③环节测评·强类型收口）。 */
export interface EvalReport {
  stages: StageReport[]
  totalPassed: number
  totalCases: number
}

/**
 * 触发全量黄金样例评测：POST /eval/run（携带 {@code X-Eval-Token} 头）。
 * @param token 评测令牌（dev 默认 {@code dev-eval-token}）
 */
export async function runEval(token: string): Promise<EvalReport> {
  return http.post('/eval/run', undefined, {
    headers: { 'X-Eval-Token': token },
  }) as unknown as Promise<EvalReport>
}
