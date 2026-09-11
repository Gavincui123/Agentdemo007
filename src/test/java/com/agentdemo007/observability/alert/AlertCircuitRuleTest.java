package com.agentdemo007.observability.alert;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具熔断告警规则测评（Phase 17·T77 alert）。
 *
 * <p>锁定 {@link AlertConfig#standardRules()} 含 per-tool 熔断开启告警：指标快照
 * {@code tool.circuit.open.count}（{@code agent.tool.circuit.open} 计数器跨工具求和派生）
 * 大于 0 时触发 <b>P1</b> 告警——区别于 P0（模型网关熔断=服务不可用），per-tool 熔断经
 * ②每步降级（TOOL_FAILURE 话术短路）使平台仍可用，故列 P1（高，需尽快处置但非致命）。
 *
 * <p>特征测试（回归守卫）：防未来改动误删该规则或改级。行为级（喂快照→经评估器→断告警），
 * 非仅"规则存在于列表"。
 */
class AlertCircuitRuleTest {

    private final List<Alert> captured = new ArrayList<>();

    private AlertRuleEvaluator evaluatorWithStandardRules() {
        return new AlertRuleEvaluator(AlertConfig.standardRules(), captured::add, this::fixedTime);
    }

    private OffsetDateTime fixedTime() {
        return OffsetDateTime.parse("2026-09-07T10:00:00+08:00");
    }

    @Test
    void standardRules_includeToolCircuitOpenP1Rule() {
        List<AlertRule> rules = AlertConfig.standardRules();

        AlertRule rule = rules.stream()
                .filter(r -> "tool-circuit-open".equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("标准规则集缺 tool-circuit-open 规则"));
        assertThat(rule.level()).isEqualTo(AlertLevel.P1);
        assertThat(rule.metric()).isEqualTo("tool.circuit.open.count");
        assertThat(rule.comparison()).isEqualTo(Comparison.GT);
        assertThat(rule.threshold()).isEqualTo(0.0);
    }

    @Test
    void circuitOpenCountAboveZero_firesP1Alert() {
        AlertRuleEvaluator evaluator = evaluatorWithStandardRules();

        List<Alert> fired = evaluator.evaluate(Map.of("tool.circuit.open.count", 1.0));

        assertThat(fired).hasSize(1);
        Alert alert = fired.get(0);
        assertThat(alert.ruleName()).isEqualTo("tool-circuit-open");
        assertThat(alert.level()).isEqualTo(AlertLevel.P1);
        assertThat(captured).containsExactly(alert);
    }

    @Test
    void circuitOpenCountZero_firesNothing() {
        AlertRuleEvaluator evaluator = evaluatorWithStandardRules();

        List<Alert> fired = evaluator.evaluate(Map.of("tool.circuit.open.count", 0.0));

        assertThat(fired).isEmpty();
        assertThat(captured).isEmpty();
    }
}
