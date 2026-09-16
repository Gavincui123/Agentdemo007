package com.agentdemo007.capability.plan;

import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntentRouteMapper} 测试（#134·7 认知→12 业务 fallbackIntent 占位映射）。
 *
 * <p>RoutePlanner 的 fallbackIntent 来源：IntentRecognitionStep(@500) 产 7 认知 Intent，
 * 本映射器翻成 12 业务意图 String（RoutePlanner/RoutePlanBaselines 的查询键）。
 *
 * <p><b>占位性质</b>（[[routeplan-design]] 缺口优先级③「12-intent 对齐」延后，blast radius 大）：
 * 三类确定性意图（闲聊/注入/转人工）直映确定性基线（RoutePlanner rule 短路零 LLM）；
 * 其余认知意图（推理/长上下文/结构化抽取/未知）→ {@code null} = 交 route_model 决策
 * （RoutePlanner 不短路，LLM 救回真实 12 意图，LLM 挂则 general_chat 兜底）。
 * 全量 7→12 枚举 replace 后，本映射器整体退役（RoutePlanStep 直接消费 12 意图枚举）。
 */
class IntentRouteMapperTest {

    private final IntentRouteMapper mapper = new IntentRouteMapper();

    @Test
    void chitChat_maps_generalChat() {
        assertThat(mapper.fallbackIntent(Intent.CHIT_CHAT)).isEqualTo("general_chat");
    }

    @Test
    void injection_maps_securityRequest() {
        assertThat(mapper.fallbackIntent(Intent.INJECTION)).isEqualTo("security_request");
    }

    @Test
    void transferToHuman_maps_degradationRequest() {
        assertThat(mapper.fallbackIntent(Intent.TRANSFER_TO_HUMAN)).isEqualTo("degradation_request");
    }

    @Test
    void reasoning_maps_null_routeViaLlm() {
        assertThat(mapper.fallbackIntent(Intent.REASONING)).isNull();
    }

    @Test
    void longContext_maps_null_routeViaLlm() {
        assertThat(mapper.fallbackIntent(Intent.LONG_CONTEXT)).isNull();
    }

    @Test
    void structuredExtraction_maps_null_routeViaLlm() {
        assertThat(mapper.fallbackIntent(Intent.STRUCTURED_EXTRACTION)).isNull();
    }

    @Test
    void other_maps_null_routeViaLlm() {
        assertThat(mapper.fallbackIntent(Intent.OTHER)).isNull();
    }

    @Test
    void nullIntent_maps_null_neverCrashes() {
        assertThat(mapper.fallbackIntent(null)).isNull();
    }
}
