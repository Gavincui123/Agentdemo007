package com.agentdemo007.capability.plan;

import com.agentdemo007.gateway.llm.ChatLlmService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LlmRouteCandidateSource} 测试（#133·Slice 2·route_model 候选来源真实现）。
 *
 * <p>{@link RouteCandidateSource} seam 的真实现：{@code ChatLlmService.decide(prompt)}（关思考，
 * [[phase-llm-primary-backup-breaker]]）→ {@link RouteCandidateParser#parse}。
 * 模型不可用（抛 / 返回 null / 输出不可解析）→ empty，交 {@link RoutePlanner} 走 rule 兜底
 * （路由永不崩，[[degradation-and-eval-principles]]）。
 *
 * <p>ChatLlmService 是具体类（构造重），用 Mockito mock（同 {@code IntentRecognizerImplTest} 范式）；
 * 解析用真 {@link RouteCandidateParser#create}（真 Jackson 3），验 decide→parse 全链路 round-trip。
 */
class LlmRouteCandidateSourceTest {

    private final ChatLlmService llm = mock(ChatLlmService.class);
    private final LlmRouteCandidateSource source =
            new LlmRouteCandidateSource(llm, RouteCandidateParser.create());

    private static final String VALID_JSON =
            "{\"intent\":\"order_query\",\"needs_rag\":false,\"needs_business_tools\":true,"
                    + "\"required_tools\":[\"get_order_logistics\"],\"knowledge_domains\":[],"
                    + "\"risk_level\":\"low\",\"requires_workflow\":false,\"fallback_policy\":\"tool_first\"}";

    @Test
    void decide_validJsonDecided_returnsCandidate() {
        when(llm.decide(anyString(), anyString())).thenReturn(VALID_JSON);
        Optional<RoutePlanCandidate> c = source.decide("route prompt");
        assertThat(c).isPresent();
        assertThat(c.get().intent()).isEqualTo("order_query");
        assertThat(c.get().requiredTools()).containsExactly("get_order_logistics");
    }

    @Test
    void decide_garbageDecided_returnsEmpty() {
        when(llm.decide(anyString(), anyString())).thenReturn("not json at all");
        assertThat(source.decide("p")).isEmpty();
    }

    @Test
    void decide_nullDecided_returnsEmpty() {
        when(llm.decide(anyString(), anyString())).thenReturn(null);
        assertThat(source.decide("p")).isEmpty();
    }

    @Test
    void decide_llmThrows_returnsEmpty_ruleBackstop() {
        when(llm.decide(anyString(), anyString())).thenThrow(new RuntimeException("model down"));
        assertThat(source.decide("p")).isEmpty();
    }
}
