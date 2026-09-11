package com.agentdemo007.observability.alert;

/**
 * 告警分级（Phase 15·T71，dev-plan §Phase15 "配置分级告警规则（P0/P1/P2）"）。
 *
 * <ul>
 *   <li>{@link #P0} — 致命：服务不可用/熔断开启/故障转移耗尽，需立即处置；</li>
 *   <li>{@link #P1} — 高：错误率/延迟超阈值、降级频繁，需尽快处置；</li>
 *   <li>{@link #P2} — 中：资源水位/Token 用量接近上限，需关注。</li>
 * </ul>
 */
public enum AlertLevel {
    P0, P1, P2
}
