package com.agentdemo007.observability.alert;

import java.time.OffsetDateTime;

/**
 * 已触发告警（Phase 15·T71，{@link AlertRuleEvaluator#evaluate} 命中规则的产物）。
 *
 * @param ruleName    触发规则名
 * @param level       分级
 * @param actualValue 触发时的实际值
 * @param threshold   规则阈值
 * @param message     告警信息
 * @param firedAt     触发时间（注入式时钟，可测试）
 */
public record Alert(String ruleName, AlertLevel level, double actualValue, double threshold,
                    String message, OffsetDateTime firedAt) {
}
