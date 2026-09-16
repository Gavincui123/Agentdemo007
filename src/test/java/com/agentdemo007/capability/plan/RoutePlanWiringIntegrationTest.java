package com.agentdemo007.capability.plan;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutePlan 装配集成测评（#137·[[routeplan-design]]·闭环验证 #134 wiring）。
 *
 * <p>不同于单元测（mock ChatLlmService），本测用**真装配**的 Spring 上下文——
 * {@link RoutePlanConfig} 7 bean + {@code GatewayConfig} 的 {@code NoopModelExecutor}。证装配链路
 * （RoutePlanner→LlmRouteCandidateSource→ChatLlmService→NoopModelExecutor→RouteCandidateParser）
 * 在 Spring 上下文里跑通，而非仅 mock 隔离测。
 *
 * <p><b>为何强制 noop：</b>本测显式 {@code properties="llm.enabled=false"} 锁死 noop 缺省链路。
 * dev shell 可能 export 了 {@code LLM_ENABLED=true}+{@code SF_KEY}（真 route_model 可达），此时裸
 * {@code @SpringBootTest} 会打真模型——"退款" 被正确路由成 {@code refund_request}（真实候选），
 * 与本测"noop 兜底 general_chat"的断言冲突（那不是 bug，是测试越界进真模型域）。
 * noop 契约：{@code ChatLlmService.decide} 返占位非 JSON → {@link RouteCandidateParser} empty
 * → {@link RoutePlanner} rule 兜底 general_chat（{@link RoutePlan.Source#DETERMINISTIC_FALLBACK}）。
 * 真 model 冒烟须 export SF_KEY+DS_KEY，另测，见 [[phase-llm-primary-backup-breaker]]。
 *
 * <p>同 {@code PipelineModeLinearTest} 范式：@SpringBootTest 装配测（autowire 真 bean + 行为断言，
 * 非全 pipeline 跑）。证 {@link RoutePlanStep} @Component + 其真装配 deps 在 Spring 上下文协同产出 routePlan。
 */
@SpringBootTest(properties = "llm.enabled=false")
class RoutePlanWiringIntegrationTest {

    @Autowired
    private RoutePlanner planner;

    @Autowired
    private RoutePlanStep routePlanStep;

    @Test
    void planner_noopMode_returnsDeterministicFallbackGeneralChat() {
        // 默认 llm.enabled 缺省 → NoopModelExecutor → decide 返占位非 JSON → parser empty → rule 兜底
        RoutePlan plan = planner.plan("general_chat", "route prompt");
        assertThat(plan).isNotNull();
        assertThat(plan.source()).isEqualTo(RoutePlan.Source.DETERMINISTIC_FALLBACK);
        assertThat(plan.intent()).isEqualTo("general_chat");
    }

    @Test
    void planner_unknownIntent_noop_rescuesToGeneralChatFallback() {
        // 未知 intent → 不短路 → LLM(noop) → empty → rule 兜底 general_chat（路由永不崩）
        RoutePlan plan = planner.plan("mystery_intent", "prompt");
        assertThat(plan.source()).isEqualTo(RoutePlan.Source.DETERMINISTIC_FALLBACK);
        assertThat(plan.intent()).isEqualTo("general_chat");
    }

    @Test
    void routePlanStep_chitChat_wiresAndShortCircuitsGeneralChat() {
        // @Component RoutePlanStep + 真装配 deps：chit-chat→mapper→general_chat→planner rule 短路（零 LLM）
        PipelineContext ctx = new PipelineContext("w1", "你好");
        ctx.setIntent(Intent.CHIT_CHAT);

        StepOutcome out = routePlanStep.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.routePlan()).isNotNull();
        assertThat(ctx.routePlan().intent()).isEqualTo("general_chat");
        assertThat(ctx.routePlan().source()).isEqualTo(RoutePlan.Source.DETERMINISTIC_FALLBACK);
    }

    @Test
    void routePlanStep_nonChitChat_noop_producesGeneralChatFallback() {
        // 非 chit-chat → intent=OTHER→null→planner LLM(noop empty)→rule 兜底 general_chat
        PipelineContext ctx = new PipelineContext("w2", "退款");
        ctx.setIntent(Intent.OTHER);

        StepOutcome out = routePlanStep.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.routePlan()).isNotNull();
        assertThat(ctx.routePlan().intent()).isEqualTo("general_chat");
    }
}
