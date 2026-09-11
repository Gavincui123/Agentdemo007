package com.agentdemo007.eval;

import java.util.List;

/**
 * 评测单例结果（Phase 15·T69）。
 *
 * @param caseId     用例标识
 * @param passed     是否通过（所有非空期望字段匹配）
 * @param mismatches 不匹配字段说明列表（通过为空）
 */
public record CaseResult(String caseId, boolean passed, List<String> mismatches) {
}
