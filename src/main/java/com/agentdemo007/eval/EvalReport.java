package com.agentdemo007.eval;

import java.util.List;

/**
 * 评测总报告（Phase 15·T69 收口产出，第四原则·强类型，非 Map）。
 *
 * @param stages      各 stage 报告
 * @param totalPassed 总通过数
 * @param totalCases  总用例数
 */
public record EvalReport(List<StageReport> stages, int totalPassed, int totalCases) {
}
