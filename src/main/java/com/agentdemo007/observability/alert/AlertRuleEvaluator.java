package com.agentdemo007.observability.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 告警规则评估器（Phase 15·T71）。
 *
 * <p>{@link #evaluate(Map)} 拿指标快照（{@code Map<String,Double>}，指标名→值，本质 name→value bag，
 * 非 ④原则所约束的步骤间结构）逐规则比对：取 {@link AlertRule#metric()} 值（缺失→跳过该规则，不误报），
 * {@link Comparison#fires} 命中则建 {@link Alert} → best-effort 经 {@link AlertChannel} 发出
 * （②降级：通道异常 log.warn 不抛、不丢告警返回值、不影响后续规则）→ 收集返回。
 *
 * <p>plain class + @Bean 工厂（{@code AlertConfig}），注入式 {@code Supplier<OffsetDateTime>} 时钟
 * （匹配 {@code TokenBudgetChecker}/{@code FeedbackCollector} 模式，可测试）。真实指标快照来源
 * （Micrometer {@code MeterRegistry} 派生：错误率/延迟分位/熔断态）由未来定时任务/可观测面板注入，
 * 评估器对来源无感（仅消费 Map）。
 */
public class AlertRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleEvaluator.class);

    private final List<AlertRule> rules;
    private final AlertChannel channel;
    private final Supplier<OffsetDateTime> clock;

    public AlertRuleEvaluator(List<AlertRule> rules, AlertChannel channel, Supplier<OffsetDateTime> clock) {
        this.rules = rules;
        this.channel = channel;
        this.clock = clock;
    }

    /**
     * 评估指标快照，返回已触发告警并 best-effort 发通道。
     *
     * @param metrics 指标快照（指标名→值；缺失指标视为未上报，对应规则跳过不误报）
     * @return 已触发告警列表（不因通道异常而丢失）
     */
    public List<Alert> evaluate(Map<String, Double> metrics) {
        List<Alert> fired = new ArrayList<>();
        for (AlertRule rule : rules) {
            Double value = metrics.get(rule.metric());
            if (value == null) {
                continue;
            }
            if (rule.comparison().fires(value, rule.threshold())) {
                Alert alert = new Alert(rule.name(), rule.level(), value, rule.threshold(),
                        rule.message(), clock.get());
                sendBestEffort(alert);
                fired.add(alert);
            }
        }
        return fired;
    }

    /** best-effort 发送（②降级：通道异常不抛、不丢返回值、不影响后续规则）。 */
    private void sendBestEffort(Alert alert) {
        try {
            channel.send(alert);
        } catch (Exception e) {
            log.warn("告警通道发送失败（告警仍计入返回值）：rule={} level={} reason={}",
                    alert.ruleName(), alert.level(), e.getMessage());
        }
    }
}
