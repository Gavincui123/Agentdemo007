package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.session.model.StandardQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工具执行步骤测试（第四层·ToolExecutionStep {@code @Order(650)}·有界 Agent loop 消费侧）。
 *
 * <p>落地收口契约：经 {@link PipelineContext} 读写、返回 {@link StepOutcome}：
 * <ul>
 *   <li>模型出 {@code tool_calls} 且执行成功 → 写入对应通道 + Proceed；</li>
 *   <li>模型未出 {@code tool_calls} → 全通道空 + Proceed（正常对话）；</li>
 *   <li>失败结果 → {@code toolErrors} 独立通道（不混 runtimeFacts 高置信事实）；</li>
 *   <li>模型澄清话术（{@link ToolTurn#loopReply()}）→ {@code toolLoopReply}；</li>
 *   <li>工具熔断中（{@link ToolCircuitOpenException}）→ {@code ShortCircuit(TOOL_FAILURE)}
 *       话术短路（零 LLM 兜底，deg-009）。熔断路径详见 {@link ToolExecutionStepCircuitTest}。</li>
 * </ul>
 * 输入取 {@code standardQuery}（缺失回退 {@code rawInput}，与意图步骤一致）。
 */
class ToolExecutionStepTest {

    private final ToolCallExecutor executor = mock(ToolCallExecutor.class);
    private final ToolExecutionStep step = new ToolExecutionStep(executor);

    @Test
    void toolCall_fillsToolResults_proceeds() {
        when(executor.execute("计算 1+2*3")).thenReturn(new ToolTurn(
                List.of(new ToolCallResult("calc", "7", ToolCategory.COMPUTE)), null));
        PipelineContext ctx = new PipelineContext("s1", "计算 1+2*3");
        ctx.setStandardQuery(StandardQuery.of("计算 1+2*3"));

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).containsExactly("7");
    }

    @Test
    void noTool_proceeds_withEmptyResults() {
        when(executor.execute(anyString())).thenReturn(ToolTurn.empty());
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setStandardQuery(StandardQuery.of("你好"));

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).isEmpty();
    }

    @Test
    void usesStandardQuery_whenPresent() {
        when(executor.execute(eq("标准问题"))).thenReturn(ToolTurn.empty());
        PipelineContext ctx = new PipelineContext("s1", "原始");
        ctx.setStandardQuery(StandardQuery.of("标准问题"));

        step.process(ctx);

        verify(executor).execute(eq("标准问题"));
    }

    @Test
    void fallsBackToRawInput_whenStandardQueryMissing() {
        when(executor.execute(eq("原始"))).thenReturn(ToolTurn.empty());
        PipelineContext ctx = new PipelineContext("s1", "原始");

        step.process(ctx);

        verify(executor).execute(eq("原始"));
    }

    @Test
    void name_isToolExecutionStep() {
        assertThat(step.name()).isEqualTo("ToolExecutionStep");
    }

    @Test
    void process_recordsToolSuccessAndFailureCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        ToolCallExecutor okExec = mock(ToolCallExecutor.class);
        ToolCallExecutor errExec = mock(ToolCallExecutor.class);
        ToolCallExecutor openExec = mock(ToolCallExecutor.class);
        when(okExec.execute(anyString())).thenReturn(new ToolTurn(
                List.of(new ToolCallResult("calc", "7", ToolCategory.COMPUTE)), null));
        // 失败结果收口（不抛）：错误随 ToolTurn 返回 → 指标记失败
        when(errExec.execute(anyString())).thenReturn(new ToolTurn(
                List.of(errResult()), null));
        when(openExec.execute(anyString())).thenThrow(new ToolCircuitOpenException("flaky"));

        new ToolExecutionStep(okExec, metrics).process(new PipelineContext("ok", "计算 1+2"));
        new ToolExecutionStep(errExec, metrics).process(new PipelineContext("e", "查订单"));
        new ToolExecutionStep(openExec, metrics).process(new PipelineContext("f", "1/0"));

        assertThat(registry.counter("agent.tool", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.tool", "success", "false").count()).isEqualTo(2.0);
    }

    // ---- 2026-09-17 有界 Agent loop 消费侧：错误通道 + 澄清话术槽 ----

    @Test
    void toolError_routedToToolErrors_notRuntimeFacts() {
        // 失败结果不得混入 runtimeFacts 高置信事实——错误不是事实（ObjectiveDataLayer 独立渲染异常块）
        ToolCallResult err = errResult();
        when(executor.execute(anyString())).thenReturn(new ToolTurn(List.of(err), null));
        PipelineContext ctx = new PipelineContext("s1", "查订单 ORD-001");
        ctx.setStandardQuery(StandardQuery.of("查订单 ORD-001"));

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolErrors()).containsExactly(err);
        assertThat(ctx.runtimeFacts()).isEmpty();
        assertThat(ctx.toolResults()).isEmpty();
    }

    @Test
    void loopReply_writtenToContext() {
        when(executor.execute(anyString())).thenReturn(new ToolTurn(
                List.of(errResult()), "请问您要查询哪个订单号？"));
        PipelineContext ctx = new PipelineContext("s1", "帮我查一下");
        ctx.setStandardQuery(StandardQuery.of("帮我查一下"));

        step.process(ctx);

        assertThat(ctx.toolLoopReply()).isEqualTo("请问您要查询哪个订单号？");
        assertThat(ctx.toolErrors()).hasSize(1);
    }

    @Test
    void loopReplyNull_staysNull() {
        when(executor.execute(anyString())).thenReturn(ToolTurn.empty());
        PipelineContext ctx = new PipelineContext("s1", "你好");

        step.process(ctx);

        assertThat(ctx.toolLoopReply()).isNull();
        assertThat(ctx.toolErrors()).isEmpty();
    }

    private static ToolCallResult errResult() {
        return new ToolCallResult("queryOrder",
                new ToolError(ToolErrorKind.HTTP_5XX, "外部系统错误 HTTP 500", 3).toText("queryOrder"),
                ToolCategory.RUNTIME,
                new ToolError(ToolErrorKind.HTTP_5XX, "外部系统错误 HTTP 500", 3));
    }

    // ---- #135 渐进消费·source 门控（[[routeplan-design]]·风险闭环扩展）：routePlan 声明
    //       needsBusinessTools=false → 跳过工具执行（LLM 候选与兜底候选同口径）；
    //       CHIT_CHAT 快路径例外（低风险词「小模型快回复+工具取数」设计保留）----

    @Test
    void routePlanLlmSourced_needsBusinessToolsFalse_skipsTools_noExecutor() {
        // route 真实候选决策不需要业务工具 → 跳过工具执行（省一次 function-calling LLM 调用）
        PipelineContext ctx = new PipelineContext("rp1", "你好");
        ctx.setStandardQuery(StandardQuery.of("你好"));
        ctx.setRoutePlan(routePlan(false, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS));

        StepOutcome out = step.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).isEmpty();
        verifyNoInteractions(executor);
    }

    @Test
    void routePlanLlmSourced_needsBusinessToolsTrue_runsExecutor() {
        when(executor.execute(anyString())).thenReturn(new ToolTurn(
                List.of(new ToolCallResult("calc", "7", ToolCategory.COMPUTE)), null));
        PipelineContext ctx = new PipelineContext("rp2", "计算 1+2");
        ctx.setStandardQuery(StandardQuery.of("计算 1+2"));
        ctx.setRoutePlan(routePlan(true, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS));

        StepOutcome out = step.process(ctx);

        verify(executor).execute(anyString());
        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).containsExactly("7");
    }

    @Test
    void routePlanDeterministicFallback_needsBusinessToolsFalse_skipsTools() {
        // 风险闭环：兜底候选按<b>基线能力</b>门控——general_chat 基线（needsBusinessTools=false）
        // 不裸附全量工具，高风险操作不得经无人审查的工具循环执行（route_model 挂时的诚实降级）
        PipelineContext ctx = new PipelineContext("rp3", "我要退款");
        ctx.setStandardQuery(StandardQuery.of("我要退款"));
        ctx.setIntent(Intent.OTHER);
        ctx.setRoutePlan(routePlan(false, RoutePlan.Source.DETERMINISTIC_FALLBACK));

        StepOutcome out = step.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        verifyNoInteractions(executor);
    }

    @Test
    void chitChatFastPath_deterministicGeneralChat_stillRunsExecutor() {
        // 低风险词快路径保留（IntentConfig 词表风险分层配套）：CHIT_CHAT（订单/物流等词直判）
        // + general_chat 兜底计划 → 工具取数不门控（「小模型快回复+工具取数」设计）
        when(executor.execute(anyString())).thenReturn(new ToolTurn(
                List.of(new ToolCallResult("calc", "7", ToolCategory.COMPUTE)), null));
        PipelineContext ctx = new PipelineContext("rp4", "查订单");
        ctx.setStandardQuery(StandardQuery.of("查订单"));
        ctx.setIntent(Intent.CHIT_CHAT);
        ctx.setRoutePlan(routePlan(false, RoutePlan.Source.DETERMINISTIC_FALLBACK));

        StepOutcome out = step.process(ctx);

        verify(executor).execute(anyString());
        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).containsExactly("7");
    }

    // ---- helper（#135 routePlan source 门控测试）----

    private static RoutePlan routePlan(boolean needsBusinessTools, RoutePlan.Source source) {
        RoutePlanCandidate c = new RoutePlanCandidate(
                needsBusinessTools ? "order_query" : "general_chat",
                false, needsBusinessTools,
                needsBusinessTools ? List.of("get_order_logistics") : List.of(),
                List.of(),
                RiskLevel.LOW, false,
                needsBusinessTools ? FallbackPolicy.TOOL_FIRST : FallbackPolicy.SAFE_DETERMINISTIC_PATH);
        return new RoutePlan(c, source, 0.9, List.of());
    }
}
