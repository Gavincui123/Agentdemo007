package com.agentdemo007.capability.plan;

import org.junit.jupiter.api.Test;

import static com.agentdemo007.capability.plan.RoutePlan.Capability;
import static com.agentdemo007.capability.plan.RoutePlan.Mode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutePlan 模型测试（能力路由规划的数据载体）。
 *
 * <p>RoutePlan 描述一条查询该走哪些能力（RAG/Tool）、并行还是串行。由 RoutePlanner 产出，
 * CapabilityStage 消费。空 plan（chit-chat）= 跳过所有能力直接出答。
 */
class RoutePlanTest {

    @Test
    void none_isEmpty_noCapabilities() {
        RoutePlan p = RoutePlan.none();
        assertThat(p.isEmpty()).isTrue();
        assertThat(p.mode()).isEqualTo(Mode.NONE);
        assertThat(p.needs(Capability.RAG)).isFalse();
        assertThat(p.needs(Capability.TOOL)).isFalse();
    }

    @Test
    void singleCapability_needsIt() {
        RoutePlan p = RoutePlan.of(Capability.RAG);
        assertThat(p.needs(Capability.RAG)).isTrue();
        assertThat(p.needs(Capability.TOOL)).isFalse();
        assertThat(p.isEmpty()).isFalse();
    }

    @Test
    void bothParallel_needsBoth() {
        RoutePlan p = RoutePlan.bothParallel();
        assertThat(p.needs(Capability.RAG)).isTrue();
        assertThat(p.needs(Capability.TOOL)).isTrue();
        assertThat(p.mode()).isEqualTo(Mode.PARALLEL);
    }

    @Test
    void bothSerial_carriesOrder() {
        RoutePlan p = RoutePlan.bothSerial(Capability.RAG, Capability.TOOL);
        assertThat(p.mode()).isEqualTo(Mode.SERIAL);
        assertThat(p.serialOrder()).containsExactly(Capability.RAG, Capability.TOOL);
    }

    @Test
    void emptyCapabilitiesForcesNoneMode() {
        // 构造空能力集时，mode 归一为 NONE（防御）
        RoutePlan p = new RoutePlan(java.util.Set.of(), Mode.PARALLEL, java.util.List.of());
        assertThat(p.mode()).isEqualTo(Mode.NONE);
        assertThat(p.isEmpty()).isTrue();
    }
}
