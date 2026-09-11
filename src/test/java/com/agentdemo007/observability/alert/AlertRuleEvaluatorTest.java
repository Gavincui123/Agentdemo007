package com.agentdemo007.observability.alert;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 告警规则评估器测试（Phase 15·T71 分级告警）。
 *
 * <p>{@link AlertRuleEvaluator#evaluate} 拿指标快照 {@code Map<String,Double>} 逐规则比对
 * （comparison+threshold）→ 命中建 {@link Alert} → best-effort 经 {@link AlertChannel} 发出
 * （②降级：通道异常不丢告警、不抛、不影响后续规则）→ 返回已触发告警列表。
 *
 * <p>注入受控规则（error.rate GT 0.05 → P0）+ 录制/抛异常通道 + 固定时钟，隔离真实 Micrometer。
 */
class AlertRuleEvaluatorTest {

    private AlertRule errorRateRule() {
        return new AlertRule("error-rate", AlertLevel.P0, "error.rate", Comparison.GT, 0.05, "错误率超阈值");
    }

    private final List<Alert> captured = new ArrayList<>();

    private AlertChannel recordingChannel() {
        return captured::add;
    }

    private OffsetDateTime fixedTime() {
        return OffsetDateTime.parse("2026-09-07T10:00:00+08:00");
    }

    private AlertRuleEvaluator evaluator(AlertChannel channel) {
        return new AlertRuleEvaluator(List.of(errorRateRule()), channel, this::fixedTime);
    }

    @Test
    void evaluate_metricExceedsThreshold_firesAlertAndSendsToChannel() {
        AlertRuleEvaluator evaluator = evaluator(recordingChannel());

        List<Alert> fired = evaluator.evaluate(Map.of("error.rate", 0.10));

        assertThat(fired).hasSize(1);
        Alert alert = fired.get(0);
        assertThat(alert.ruleName()).isEqualTo("error-rate");
        assertThat(alert.level()).isEqualTo(AlertLevel.P0);
        assertThat(alert.actualValue()).isCloseTo(0.10, within(0.0001));
        assertThat(alert.threshold()).isCloseTo(0.05, within(0.0001));
        assertThat(alert.message()).isEqualTo("错误率超阈值");
        assertThat(alert.firedAt()).isEqualTo(fixedTime());
        assertThat(captured).containsExactly(alert);
    }

    @Test
    void evaluate_metricBelowThreshold_firesNothing() {
        AlertRuleEvaluator evaluator = evaluator(recordingChannel());

        List<Alert> fired = evaluator.evaluate(Map.of("error.rate", 0.01));

        assertThat(fired).isEmpty();
        assertThat(captured).isEmpty();
    }

    @Test
    void evaluate_metricAbsent_firesNothing() {
        AlertRuleEvaluator evaluator = evaluator(recordingChannel());

        List<Alert> fired = evaluator.evaluate(Map.of());

        assertThat(fired).isEmpty();
        assertThat(captured).isEmpty();
    }

    @Test
    void evaluate_channelThrows_doesNotLoseAlertAndDoesNotThrow() {
        AlertChannel throwing = alert -> { throw new RuntimeException("channel down"); };
        AlertRuleEvaluator evaluator = evaluator(throwing);

        List<Alert> fired = evaluator.evaluate(Map.of("error.rate", 0.10));

        assertThat(fired).hasSize(1);
    }
}
