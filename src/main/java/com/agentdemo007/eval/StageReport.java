package com.agentdemo007.eval;

import java.util.List;

/**
 * 单 stage 评测报告（Phase 15·T69，按 stage 分组）。
 *
 * @param stage    stage 名
 * @param passed   通过用例数
 * @param total    总用例数
 * @param passRate 通过率（0.0–1.0）
 * @param cases    每例结果
 * @param source   黄金数据来源（nacos=本次实时拉取的 Nacos 数据 / local=本地 classpath 兜底）
 */
public record StageReport(String stage, int passed, int total, double passRate,
                          List<CaseResult> cases, String source) {
}
