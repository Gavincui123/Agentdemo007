package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.StandardQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一收口（第四原则）：上下文载体与步骤产出契约的纯单元测试。
 *
 * <p>收口三件套之一 {@code PipelineContext}——贯穿全部 7 层步骤的唯一状态载体，
 * 每步读写同一份字段契约，禁止各步私造数据结构互相传递（防漂移）。
 *
 * <p>收口三件套之二 {@code StepOutcome}——每步统一产出（sealed：Proceed/ShortCircuit/Degrade），
 * 把「话术短路 + 每步降级 + 统一收口」机械统一为同一出口形状。
 */
class PipelineContextTest {

    @Test
    void context_holdsSessionAndRawInput() {
        PipelineContext ctx = new PipelineContext("sess-1", "你好");
        assertThat(ctx.sessionId()).isEqualTo("sess-1");
        assertThat(ctx.rawInput()).isEqualTo("你好");
    }

    @Test
    void context_finalReply_roundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        ctx.setFinalReply("您好");
        assertThat(ctx.finalReply()).isEqualTo("您好");
    }

    @Test
    void context_markDegraded_setsFlagAndScenario() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.degraded()).isFalse();
        ctx.markDegraded(DegradationScenario.SESSION_DOWN);
        assertThat(ctx.degraded()).isTrue();
        assertThat(ctx.scenario()).isEqualTo(DegradationScenario.SESSION_DOWN);
    }

    @Test
    void outcome_proceed_isSealedSubtype() {
        StepOutcome o = new StepOutcome.Proceed();
        assertThat(o).isInstanceOf(StepOutcome.Proceed.class);
    }

    @Test
    void outcome_shortCircuit_carriesScenario() {
        StepOutcome.ShortCircuit sc = new StepOutcome.ShortCircuit(DegradationScenario.INJECTION);
        assertThat(sc.scenario()).isEqualTo(DegradationScenario.INJECTION);
    }

    @Test
    void outcome_degrade_carriesScenario() {
        StepOutcome.Degrade d = new StepOutcome.Degrade(DegradationScenario.MODEL_DOWN);
        assertThat(d.scenario()).isEqualTo(DegradationScenario.MODEL_DOWN);
    }

    @Test
    void history_defaultsEmpty() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.history()).isEmpty();
    }

    @Test
    void history_setAndAppend_roundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        ctx.setHistory(List.of(new ChatMessage.User("你好")));
        ctx.appendHistory(new ChatMessage.Ai("您好"));
        assertThat(ctx.history()).hasSize(2);
        assertThat(ctx.history().get(0)).isInstanceOf(ChatMessage.User.class);
        assertThat(ctx.history().get(1).content()).isEqualTo("您好");
    }

    // ---- Phase 6：会话理解层强类型字段（summary / standardQuery） ----

    @Test
    void summary_defaultsNull_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.summary()).isNull();
        ctx.setSummary("会话主题：Q3 销售");
        assertThat(ctx.summary()).isEqualTo("会话主题：Q3 销售");
    }

    @Test
    void standardQuery_defaultsNull_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "它怎么样");
        assertThat(ctx.standardQuery()).isNull();
        ctx.setStandardQuery(StandardQuery.of("Q3 销售额怎么样"));
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("Q3 销售额怎么样"));
    }

    // ---- Phase 7 意图与路由强类型字段（§5.14：intent/routeType/selectedModelId 收口于此） ----

    @Test
    void intent_andConfidence_defaultAndRoundTrip() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.intent()).isNull();
        assertThat(ctx.intentConfidence()).isZero();
        ctx.setIntent(Intent.REASONING);
        ctx.setIntentConfidence(0.92);
        assertThat(ctx.intent()).isEqualTo(Intent.REASONING);
        assertThat(ctx.intentConfidence()).isEqualTo(0.92);
    }

    @Test
    void routeType_andSelectedModel_defaultAndRoundTrip() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.routeType()).isNull();
        assertThat(ctx.selectedModelId()).isNull();
        ctx.setRouteType(RouteRule.RouteType.REASONING);
        ctx.setSelectedModelId("gpt-4o");
        assertThat(ctx.routeType()).isEqualTo(RouteRule.RouteType.REASONING);
        assertThat(ctx.selectedModelId()).isEqualTo("gpt-4o");
    }

    // ---- Phase 8 上下文构建工厂强类型字段（§5.14：ragFragments/toolResults/assembledPrompt 收口于此） ----

    @Test
    void ragFragments_defaultsEmpty_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.ragFragments()).isEmpty();
        ctx.setRagFragments(List.of("片段A", "片段B"));
        assertThat(ctx.ragFragments()).containsExactly("片段A", "片段B");
    }

    @Test
    void toolResults_defaultsEmpty_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.toolResults()).isEmpty();
        ctx.setToolResults(List.of("工具结果1"));
        assertThat(ctx.toolResults()).containsExactly("工具结果1");
    }

    @Test
    void assembledPrompt_defaultsEmpty_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.assembledPrompt()).isEmpty();
        ctx.setAssembledPrompt(List.of(
                new ChatMessage.System("sys"),
                new ChatMessage.User("u")));
        assertThat(ctx.assembledPrompt()).hasSize(2);
        assertThat(ctx.assembledPrompt().get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(ctx.assembledPrompt().get(1)).isInstanceOf(ChatMessage.User.class);
    }

    // ---- Phase 11/12 强类型字段（§5.14：hitlTicketId/modelResponse 收口于此） ----

    @Test
    void hitlTicketId_defaultsNull_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.hitlTicketId()).isNull();
        ctx.setHitlTicketId("hitl-1738");
        assertThat(ctx.hitlTicketId()).isEqualTo("hitl-1738");
    }

    @Test
    void modelResponse_defaultsNull_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.modelResponse()).isNull();
        ctx.setModelResponse("[dev noop] 模型执行器未配置");
        assertThat(ctx.modelResponse()).isEqualTo("[dev noop] 模型执行器未配置");
    }

    // ---- Phase 13 审计事件强类型字段（§5.14：审计点收集于此，终端后置钩子刷出） ----

    @Test
    void auditEvents_defaultsEmpty() {
        PipelineContext ctx = new PipelineContext("s", "x");
        assertThat(ctx.auditEvents()).isEmpty();
    }

    @Test
    void addAuditEvent_accumulatesInOrder() {
        PipelineContext ctx = new PipelineContext("trace-1", "sess-1", "x");
        ctx.addAuditEvent(AuditEvent.of(AuditEventType.INJECTION, "trace-1", "sess-1", "注入命中"));
        ctx.addAuditEvent(AuditEvent.of(AuditEventType.HITL, "trace-1", "sess-1", "转人工"));

        assertThat(ctx.auditEvents()).hasSize(2);
        assertThat(ctx.auditEvents().get(0).type()).isEqualTo(AuditEventType.INJECTION);
        assertThat(ctx.auditEvents().get(1).type()).isEqualTo(AuditEventType.HITL);
    }
}
