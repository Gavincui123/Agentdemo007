package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 步骤测试（第四层·{@code @Order(610)}，紧随 RouteDispatch(600)、先于 Tool(650)）。
 *
 * <p>覆盖 §5.4.3 + §5.12 HITL 行：触发→建工单→超时/权限判定→{@code ShortCircuit(HITL_TIMEOUT)} 话术 + 工单
 * （不自动执行高风险、不阻塞主链路：立即返回话术而非挂起请求）。
 * 触发条件：意图=转人工 或 命中高风险关键词；非触发→Proceed。
 */
class HitlStepTest {

    private final HitlHandler handler = new HitlHandler();
    private final HumanTicketService ticketService = new HumanTicketService();

    private HitlStep newStep(HitlDecision decision) {
        return new HitlStep(handler, decision, ticketService);
    }

    private HitlDecision decision(Duration timeout, DecisionResolver resolver, PermissionChecker permission) {
        return new HitlDecision(timeout, resolver, permission);
    }

    private PipelineContext ctx(Intent intent, String query) {
        PipelineContext c = new PipelineContext("sess-1", query);
        c.setIntent(intent);
        return c;
    }

    @Test
    void notTriggered_proceeds_noTicket() {
        HitlStep step = newStep(decision(Duration.ofMinutes(5), DecisionResolver.none(), PermissionChecker.alwaysPermitted()));

        StepOutcome out = step.process(ctx(Intent.CHIT_CHAT, "查一下我的订单"));

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ticketService.all()).isEmpty();
        assertThat(new PipelineContext("s", "x").hitlTicketId()).isNull();
    }

    @Test
    void transferToHuman_pendingShortCircuitsHitlTimeout() {
        HitlStep step = newStep(decision(Duration.ofMinutes(5), DecisionResolver.none(), PermissionChecker.alwaysPermitted()));

        StepOutcome out = step.process(ctx(Intent.TRANSFER_TO_HUMAN, "帮我转人工"));

        assertThat(out).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) out).scenario()).isEqualTo(DegradationScenario.HITL_TIMEOUT);
        assertThat(ticketService.all()).hasSize(1);
        HumanTicket ticket = ticketService.all().get(0);
        assertThat(ticket.status()).isEqualTo(HumanTicket.Status.PENDING);
    }

    @Test
    void keywordTrigger_shortCircuitsHitlTimeout() {
        HitlStep step = newStep(decision(Duration.ofMinutes(5), DecisionResolver.none(), PermissionChecker.alwaysPermitted()));

        StepOutcome out = step.process(ctx(Intent.OTHER, "我要投诉订单"));

        assertThat(out).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) out).scenario()).isEqualTo(DegradationScenario.HITL_TIMEOUT);
        assertThat(ticketService.all()).hasSize(1);
    }

    @Test
    void timeout_marksTimeoutAndShortCircuits() {
        // timeout=0：createTicket 与 decide 同一 now → now >= createdAt+0 → Timeout
        HitlStep step = newStep(decision(Duration.ZERO, DecisionResolver.none(), PermissionChecker.alwaysPermitted()));

        StepOutcome out = step.process(ctx(Intent.TRANSFER_TO_HUMAN, "转人工"));

        assertThat(out).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) out).scenario()).isEqualTo(DegradationScenario.HITL_TIMEOUT);
        assertThat(ticketService.all().get(0).status()).isEqualTo(HumanTicket.Status.TIMEOUT);
    }

    @Test
    void permissionDenied_shortCircuitsHitlTimeout() {
        HitlStep step = newStep(decision(Duration.ofMinutes(5), DecisionResolver.none(), (s, r) -> false));

        StepOutcome out = step.process(ctx(Intent.TRANSFER_TO_HUMAN, "转人工"));

        assertThat(out).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) out).scenario()).isEqualTo(DegradationScenario.HITL_TIMEOUT);
        // 权限不足→工单挂起（PENDING，未标 TIMEOUT）
        assertThat(ticketService.all().get(0).status()).isEqualTo(HumanTicket.Status.PENDING);
    }

    @Test
    void confirmed_proceedsWithTicket() {
        DecisionResolver resolver = t -> Optional.of(new HitlDecision.Confirmed("operator-1"));
        HitlStep step = newStep(decision(Duration.ofMinutes(5), resolver, (s, r) -> false));

        StepOutcome out = step.process(ctx(Intent.TRANSFER_TO_HUMAN, "转人工"));

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ticketService.all()).hasSize(1);
    }

    @Test
    void triggered_setsHitlTicketIdOnContext() {
        HitlStep step = newStep(decision(Duration.ofMinutes(5), DecisionResolver.none(), PermissionChecker.alwaysPermitted()));

        PipelineContext context = ctx(Intent.TRANSFER_TO_HUMAN, "转人工");
        step.process(context);

        assertThat(context.hitlTicketId()).isNotBlank();
    }

    @Test
    void process_recordsHitlTriggeredAndPassCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        HitlStep passStep = new HitlStep(handler,
                decision(Duration.ofMinutes(5), DecisionResolver.none(), PermissionChecker.alwaysPermitted()),
                ticketService, metrics);
        HitlStep triggerStep = new HitlStep(handler,
                decision(Duration.ofMinutes(5), DecisionResolver.none(), PermissionChecker.alwaysPermitted()),
                ticketService, metrics);

        passStep.process(ctx(Intent.CHIT_CHAT, "查订单"));
        triggerStep.process(ctx(Intent.TRANSFER_TO_HUMAN, "转人工"));

        assertThat(registry.counter("agent.hitl", "triggered", "false").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.hitl", "triggered", "true").count()).isEqualTo(1.0);
    }

    // ---- #135 渐进消费·source 门控（[[routeplan-design]]）：routePlan.source==LLM_WITH_POLICY_CONSTRAINTS
    //       时按 fallbackPolicy 决策（TRANSFER_TO_HUMAN→需人工）；DETERMINISTIC_FALLBACK/null 回退
    //       现有 handler.needsReview 逻辑（noop 测试/兜底候选不采信 routePlan，现有行为不破）----

    @Test
    void routePlanLlmSourced_transferToHuman_triggersHitl() {
        // route 真实候选 fallbackPolicy=TRANSFER_TO_HUMAN → 触发 HITL（即使 intent=CHIT_CHAT 旧逻辑不触发）
        HitlStep step = newStep(decision(Duration.ofMinutes(5),
                DecisionResolver.none(), PermissionChecker.alwaysPermitted()));
        PipelineContext c = ctx(Intent.CHIT_CHAT, "查订单");
        c.setRoutePlan(routePlan(FallbackPolicy.TRANSFER_TO_HUMAN, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS));

        StepOutcome out = step.process(c);

        assertThat(out).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) out).scenario()).isEqualTo(DegradationScenario.HITL_TIMEOUT);
        assertThat(ticketService.all()).hasSize(1);
    }

    @Test
    void routePlanLlmSourced_safeDeterministicPath_skipsHitl() {
        // route 真实候选 fallbackPolicy=SAFE_DETERMINISTIC_PATH → 不触发（route 决策无需人工）
        HitlStep step = newStep(decision(Duration.ofMinutes(5),
                DecisionResolver.none(), PermissionChecker.alwaysPermitted()));
        PipelineContext c = ctx(Intent.CHIT_CHAT, "查订单");
        c.setRoutePlan(routePlan(FallbackPolicy.SAFE_DETERMINISTIC_PATH, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS));

        StepOutcome out = step.process(c);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ticketService.all()).isEmpty();
    }

    @Test
    void routePlanDeterministicFallback_chitChat_skipsViaOldHandler() {
        // source=DETERMINISTIC_FALLBACK → 不采信 routePlan，回退现有 handler.needsReview（chit-chat→false）
        HitlStep step = newStep(decision(Duration.ofMinutes(5),
                DecisionResolver.none(), PermissionChecker.alwaysPermitted()));
        PipelineContext c = ctx(Intent.CHIT_CHAT, "查订单");
        c.setRoutePlan(routePlan(FallbackPolicy.TRANSFER_TO_HUMAN, RoutePlan.Source.DETERMINISTIC_FALLBACK));

        StepOutcome out = step.process(c);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ticketService.all()).isEmpty(); // 旧逻辑：chit-chat 不触发
    }

    @Test
    void routePlanDeterministicFallback_transferToHuman_triggersViaOldHandler() {
        // fallback→不采信 routePlan；旧逻辑：intent=TRANSFER_TO_HUMAN→handler.needsReview true→触发
        HitlStep step = newStep(decision(Duration.ofMinutes(5),
                DecisionResolver.none(), PermissionChecker.alwaysPermitted()));
        PipelineContext c = ctx(Intent.TRANSFER_TO_HUMAN, "转人工");
        c.setRoutePlan(routePlan(FallbackPolicy.SAFE_DETERMINISTIC_PATH, RoutePlan.Source.DETERMINISTIC_FALLBACK));

        StepOutcome out = step.process(c);

        assertThat(out).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(ticketService.all()).hasSize(1);
    }

    // ---- helper（#135 routePlan source 门控测试）----

    private static RoutePlan routePlan(FallbackPolicy fallback, RoutePlan.Source source) {
        boolean transfer = fallback == FallbackPolicy.TRANSFER_TO_HUMAN;
        RoutePlanCandidate c = new RoutePlanCandidate(
                transfer ? "security_request" : "general_chat",
                false, false, List.of(), List.of(),
                transfer ? RiskLevel.HIGH : RiskLevel.LOW,
                false, fallback);
        return new RoutePlan(c, source, 0.9, List.of());
    }
}
