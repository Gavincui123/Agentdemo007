package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 业务幂等键解析器测试（2026-09-18 L2·用户裁决）。
 *
 * <p>键必须锚定<b>业务唯一属性（订单号）+ 动作</b>而非会话——用户重新发起会话后仍有防重保障：
 * <ul>
 *   <li>订单号提取 + 归一（ord-001 → ORD-001，跨大小写/跨会话稳定）；</li>
 *   <li>动作取 routePlan 候选 route id 优先，回退意图枚举名；</li>
 *   <li>无订单号 → 查询文本摘要兜底（同话术跨会话仍同键）；</li>
 *   <li>确定性：同输入恒同键（幂等的前提）。</li>
 * </ul>
 */
class HitlIdempotencyKeyResolverTest {

    private final HitlIdempotencyKeyResolver resolver = new HitlIdempotencyKeyResolver();

    private static PipelineContext ctx(String query, String routeIntent, Intent intent) {
        PipelineContext c = new PipelineContext("sess-1", query);
        c.setIntent(intent);
        if (routeIntent != null) {
            RoutePlanCandidate candidate = new RoutePlanCandidate(routeIntent, false, false,
                    java.util.List.of(), java.util.List.of(), RiskLevel.HIGH, false,
                    FallbackPolicy.TRANSFER_TO_HUMAN);
            c.setRoutePlan(new RoutePlan(candidate, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, java.util.List.of()));
        }
        return c;
    }

    @Test
    void orderId_extractedAndNormalized_composesBusinessKey() {
        String key = resolver.resolve(ctx("我要退款 ord-001 可以吗", "refund", Intent.TRANSFER_TO_HUMAN));

        assertThat(key).isEqualTo("hitl:REFUND:ORD-001"); // 动作与订单号均归一大写（确定性归一）
    }

    @Test
    void sameOrder_differentSessions_sameKey() {
        // 跨会话稳定（幂等键的核心诉求）：sessionId 不同、话术大小写不同 → 同键
        PipelineContext a = new PipelineContext("sess-A", "我要退款 ORD-001");
        a.setRoutePlan(ctx("x", "refund", Intent.TRANSFER_TO_HUMAN).routePlan());
        PipelineContext b = new PipelineContext("sess-B", "我要退款 ord-001");
        b.setRoutePlan(ctx("x", "refund", Intent.TRANSFER_TO_HUMAN).routePlan());

        assertThat(resolver.resolve(a)).isEqualTo(resolver.resolve(b));
        assertThat(resolver.resolve(a)).isEqualTo("hitl:REFUND:ORD-001");
    }

    @Test
    void differentOrders_differentKeys() {
        String k1 = resolver.resolve(ctx("退款 ORD-001", "refund", Intent.TRANSFER_TO_HUMAN));
        String k2 = resolver.resolve(ctx("退款 ORD-002", "refund", Intent.TRANSFER_TO_HUMAN));

        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void actionFallsBackToIntentEnum_whenNoRoutePlan() {
        String key = resolver.resolve(ctx("转人工 ORD-007", null, Intent.TRANSFER_TO_HUMAN));

        assertThat(key).isEqualTo("hitl:TRANSFER_TO_HUMAN:ORD-007");
    }

    @Test
    void noOrderId_fallsBackToQueryDigest_sessionIndependent() {
        // 兜底：无订单号 → 查询摘要（同话术跨会话仍同键；不同话术不同键）
        String k1 = resolver.resolve(ctx("帮我转人工处理", null, Intent.TRANSFER_TO_HUMAN));
        PipelineContext sess2 = new PipelineContext("sess-2", "帮我转人工处理");
        sess2.setIntent(Intent.TRANSFER_TO_HUMAN);
        String k2 = resolver.resolve(sess2);
        String k3 = resolver.resolve(ctx("帮我转人工 处理退费", null, Intent.TRANSFER_TO_HUMAN));

        assertThat(k1).isEqualTo(k2); // 跨会话同话术 → 同键
        assertThat(k1).startsWith("hitl:TRANSFER_TO_HUMAN:q:");
        assertThat(k1).isNotEqualTo(k3); // 不同话术 → 不同键
    }

    @Test
    void deterministic_sameInputSameKey() {
        PipelineContext c = ctx("退货 ORD-003", "return", Intent.OTHER);

        assertThat(resolver.resolve(c)).isEqualTo(resolver.resolve(c));
    }
}
