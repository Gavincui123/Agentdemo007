package com.agentdemo007.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具熔断指标单测（Phase 17·T77）。
 *
 * <p>验 {@link AgentMetrics#recordToolCircuitOpen(String)} 打点 {@code agent.tool.circuit.open}
 * 计数器（tool 标签=工具名）——区别于 {@link AgentMetrics#recordTool(boolean)} 的成功/失败计数，
 * 专供 P1 告警规则 {@code agent.tool.circuit.open>0} 消费（§5.9 可观测）。
 */
class AgentMetricsCircuitTest {

    @Test
    void recordToolCircuitOpen_incrementsCounterByTool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordToolCircuitOpen("flaky");
        metrics.recordToolCircuitOpen("flaky");
        metrics.recordToolCircuitOpen("search");

        assertThat(registry.counter("agent.tool.circuit.open", "tool", "flaky").count()).isEqualTo(2.0);
        assertThat(registry.counter("agent.tool.circuit.open", "tool", "search").count()).isEqualTo(1.0);
    }
}
