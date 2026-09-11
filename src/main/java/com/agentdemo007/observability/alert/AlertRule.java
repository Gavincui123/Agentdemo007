package com.agentdemo007.observability.alert;

/**
 * 告警规则（Phase 15·T71，强类型 ④收口，非 Map）。
 *
 * <p>一条规则 = 监控某 {@code metric}，按 {@code comparison} 与 {@code threshold} 比对，
 * 命中则发 {@code level} 级告警，附 {@code message}。规则可配置驱动（name/level/metric/
 * comparison/threshold/message 均可 JSON 序列化，未来可经 Nacos 热加载）。
 *
 * @param name       规则名（唯一标识）
 * @param level      告警分级
 * @param metric     指标名（指标快照 Map 的键，如 error.rate/p95.latency.ms）
 * @param comparison 比较算子
 * @param threshold  阈值
 * @param message    告警信息（ops 可读，非用户面）
 */
public record AlertRule(String name, AlertLevel level, String metric,
                        Comparison comparison, double threshold, String message) {
}
