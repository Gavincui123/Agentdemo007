package com.agentdemo007.observability.alert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 告警装配（Phase 15·T71）。
 *
 * <p>{@link AlertRuleEvaluator}（plain class，注入式时钟）+ {@link AlertChannel}（seam，dev NO_OP）
 * 的 @Bean 工厂。标准 P0/P1/P2 规则集由 {@link #standardRules()} 提供——不注册为 {@code List<AlertRule>}
 * bean（避 Spring 对 {@code List<X>} 注入按"所有 X bean"收集的歧义），而由工厂内联传入评估器。
 *
 * <p>规则可配置驱动（{@link AlertRule} 各字段可 JSON 序列化），未来经 Nacos 热加载时替换本工厂即可，
 * 评估器对规则来源无感。真实指标快照（MeterRegistry 派生）的定时拉取 + 调用评估器属可观测面板职责，
 * 此处仅装配评估器与通道。
 */
@Configuration
public class AlertConfig {

    @Bean
    @ConditionalOnMissingBean(AlertChannel.class)
    AlertChannel alertChannel() {
        return AlertChannel.NO_OP;
    }

    @Bean
    AlertRuleEvaluator alertRuleEvaluator(AlertChannel channel) {
        return new AlertRuleEvaluator(standardRules(), channel, OffsetDateTime::now);
    }

    /** 标准 P0/P1/P2 规则集（dev-plan §Phase15 分级告警，指标名对齐 AgentMetrics 派生快照）。 */
    static List<AlertRule> standardRules() {
        return List.of(
                new AlertRule("error-rate", AlertLevel.P0, "error.rate", Comparison.GT, 0.05, "错误率超阈值"),
                new AlertRule("failover-exhausted", AlertLevel.P0, "failover.exhausted.count",
                        Comparison.GT, 0, "故障转移候选耗尽"),
                new AlertRule("p95-latency", AlertLevel.P1, "p95.latency.ms", Comparison.GT, 3000, "P95延迟超阈值"),
                new AlertRule("degradation-rate", AlertLevel.P1, "degradation.rate",
                        Comparison.GT, 0.2, "降级率超阈值"),
                new AlertRule("tool-circuit-open", AlertLevel.P1, "tool.circuit.open.count",
                        Comparison.GT, 0, "工具熔断开启（per-tool 持续不可用，经降级平台仍可用）"),
                new AlertRule("token-usage", AlertLevel.P2, "token.usage.ratio",
                        Comparison.GT, 0.9, "Token用量接近上限"));
    }
}
