package com.agentdemo007.capability.tool;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.TokenBudgetChecker;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.gateway.llm.GatewayChatModel;
import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.resilience.CircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.resilience.ToolHttpException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolCallExecutor} 测试——<b>有界 Agent loop</b>（2026-09-17 推翻单次前向·用户裁决）。
 *
 * <p>证 loop 骨架：探测（{@code GatewayChatModel#doChat}，messages 累积多轮）→ 执行
 * （{@link ResilientToolExecutor} 包 propagating/wrap 双开的 {@code DefaultToolExecutor}）→
 * 失败回喂（{@code AiMessage(tool_calls)} + {@code ToolExecutionResultMessage} 配对回传）→
 * 模型自纠正/澄清/轮次耗尽三出口。脚本化网关出多轮罐装 {@link LlmResponse}（队列化执行器），
 * 免 ModelConfigCenter 装配耦合。韧性语义（重试/退避/熔断/超时）详 {@link ResilientToolExecutorTest}
 * ——此处 noRetry 口径隔离 loop 语义。
 */
class ToolCallExecutorTest {

    /** 队列化脚本执行器：按序弹出罐装 LlmResponse（多轮 loop 脚本），记录模型调用次数。 */
    static final class QueuedModelExecutor implements ModelExecutor {
        private final Deque<LlmResponse> script;
        final AtomicInteger calls = new AtomicInteger();

        QueuedModelExecutor(List<LlmResponse> script) {
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public LlmResponse execute(LlmRequest request) {
            calls.incrementAndGet();
            return script.poll();
        }
    }

    /** @Tool 桩：前 failTimes 次抛 ToolHttpException(status)（propagate 后经 LC4j 包装），其后成功。 */
    static class FlakyHttpTool {
        final AtomicInteger calls = new AtomicInteger();
        final int failTimes;
        final int status;

        FlakyHttpTool(int failTimes, int status) {
            this.failTimes = failTimes;
            this.status = status;
        }

        @Tool("模拟外部系统查询")
        @SuppressWarnings("unused")
        String call() {
            if (calls.incrementAndGet() <= failTimes) {
                throw new ToolHttpException(status, "外部系统错误 HTTP " + status);
            }
            return "外部数据";
        }
    }

    private static FlowControlPolicy noLimit() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(60));
    }

    /** propagating/wrap 双开原语 + noRetry 韧性（隔离 loop 语义与重试语义）。 */
    private static ResilientToolExecutor noRetryExecutor(Object bean, Method method,
                                                         ToolCircuitBreaker breaker) throws Exception {
        DefaultToolExecutor delegate = new DefaultToolExecutor.Builder()
                .object(bean).originalMethod(method).methodToInvoke(method)
                .wrapToolArgumentsExceptions(Boolean.TRUE)
                .propagateToolExecutionExceptions(Boolean.TRUE)
                .build();
        return new ResilientToolExecutor(delegate, breaker); // 兼容构造：noRetry/无超时
    }

    /** 工具环节受测对象：脚本化网关（buildChatModel 覆写）+ 注入执行器映射 + 轮次上限。 */
    private ToolCallExecutor executorWith(QueuedModelExecutor modelExec,
                                          Map<String, ResilientToolExecutor> executors,
                                          List<ToolSpecification> specs, int maxRounds) {
        UnifiedModelGateway gateway = new UnifiedModelGateway(
                modelExec, new TokenBudgetChecker(), new com.agentdemo007.gateway.core.FailoverExecutor());
        return new ToolCallExecutor(gateway, null, 512, specs, executors, Map.of(), maxRounds) {
            @Override
            protected GatewayChatModel buildChatModel() {
                return new GatewayChatModel(gateway, "test-small", 512,
                        FailoverPolicy.builder("fo").build(), noLimit(), true);
            }
        };
    }

    private static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static LlmResponse toolCallResp(ToolExecutionRequest... calls) {
        return new LlmResponse("test-small", null, 5, List.of(calls));
    }

    private static LlmResponse textResp(String text) {
        return new LlmResponse("test-small", text, 8, List.of());
    }

    // ---- 基线（单轮即止路径）----

    @Test
    void execute_toolCall_dispatchesRealTool_returnsResults() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method m = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(
                toolCallResp(call("c1", "triangleArea", "{\"base\":3,\"height\":4}"))));
        ToolCallExecutor executor = executorWith(model,
                Map.of("triangleArea", noRetryExecutor(tool, m, breaker)), List.of(spec), 2);

        ToolTurn turn = executor.execute("底3高4的三角形面积");

        // 真派发原语（propagating DefaultToolExecutor）+ 真 @Tool 算出 6
        assertThat(turn.results()).hasSize(1);
        assertThat(turn.results().get(0).content()).isEqualTo("6");
        assertThat(turn.results().get(0).isError()).isFalse();
        assertThat(turn.loopReply()).isNull();
        // 全成功即停：仅 1 次模型调用（不空转烧轮次）
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void execute_noToolCall_returnsEmptyTurn() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method m = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(textResp("这个问题不需要工具")));
        ToolCallExecutor executor = executorWith(model,
                Map.of("triangleArea", noRetryExecutor(tool, m, breaker)),
                List.of(ToolSpecifications.toolSpecificationFrom(m)), 2);

        ToolTurn turn = executor.execute("你好");

        assertThat(turn.results()).isEmpty(); // 正常对话，不触发工具
        assertThat(turn.loopReply()).isNull();
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test
    void execute_breakerOpen_propagatesToolCircuitOpenException() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method m = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        breaker.recordFailure(spec.name()); // 预开
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(
                toolCallResp(call("c1", "triangleArea", "{\"base\":3,\"height\":4}"))));
        ToolCallExecutor executor = executorWith(model,
                Map.of("triangleArea", noRetryExecutor(tool, m, breaker)), List.of(spec), 2);

        assertThatThrownBy(() -> executor.execute("底3高4的三角形面积"))
                .isInstanceOf(ToolCircuitOpenException.class)
                .hasMessageContaining(spec.name());
    }

    @Test
    void execute_noChatRoute_returnsEmpty_gracefulDegrade() throws Exception {
        // dev/未装配：center 未 refresh（current=null）→ routeFor(CHIT_CHAT) 空 → buildChatModel 返 null
        TriangleAreaTool tool = new TriangleAreaTool();
        Method m = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        ModelConfigCenter emptyCenter = new ModelConfigCenter(() -> null, new ModelRegistry());
        ToolCallExecutor executor = new ToolCallExecutor(null, emptyCenter, 512,
                List.of(ToolSpecifications.toolSpecificationFrom(m)),
                Map.of("triangleArea", noRetryExecutor(tool, m, breaker)), Map.of(), 2);

        assertThat(executor.execute("底3高4的三角形面积").results()).isEmpty();
    }

    // ---- Agent loop：失败回喂自纠正 ----

    @Test
    void loop_http500Error_fedBack_modelSelfCorrects_secondCallSucceeds() throws Exception {
        FlakyHttpTool tool = new FlakyHttpTool(1, 500); // 首调 500，重调成功
        Method m = FlakyHttpTool.class.getDeclaredMethod("call");
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(5, 30000, () -> 0); // 阈值高于 loop 轮次
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(
                toolCallResp(call("c1", "call", "{}")),
                toolCallResp(call("c2", "call", "{}"))));
        ToolCallExecutor executor = executorWith(model,
                Map.of("call", noRetryExecutor(tool, m, breaker)), List.of(spec), 2);

        ToolTurn turn = executor.execute("查外部数据");

        // 第 1 轮错误结果（结构化回喂）+ 第 2 轮自纠正成功
        assertThat(turn.results()).hasSize(2);
        assertThat(turn.results().get(0).isError()).isTrue();
        assertThat(turn.results().get(0).error().kind()).isEqualTo(ToolErrorKind.HTTP_5XX);
        assertThat(turn.results().get(0).content()).contains("\"toolError\":true");
        assertThat(turn.results().get(1).content()).isEqualTo("外部数据");
        assertThat(turn.loopReply()).isNull();
        assertThat(model.calls.get()).isEqualTo(2); // 两轮探测（失败回喂触发第二轮）
        assertThat(tool.calls.get()).isEqualTo(2);
    }

    @Test
    void loop_modelGivesUpWithText_capturedAsLoopReply_clarificationExit() throws Exception {
        FlakyHttpTool tool = new FlakyHttpTool(99, 400); // 恒 400（不重试、不记熔断）
        Method m = FlakyHttpTool.class.getDeclaredMethod("call");
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(5, 30000, () -> 0);
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(
                toolCallResp(call("c1", "call", "{}")),
                textResp("请问您要查询哪个订单号？"))); // 模型放弃自纠正 → 转澄清
        ToolCallExecutor executor = executorWith(model,
                Map.of("call", noRetryExecutor(tool, m, breaker)), List.of(spec), 2);

        ToolTurn turn = executor.execute("帮我查一下");

        assertThat(turn.results()).hasSize(1);
        assertThat(turn.results().get(0).error().kind()).isEqualTo(ToolErrorKind.HTTP_4XX);
        // 澄清出口：模型文本交终答 LLM 整合（系统不吞异常，异常信息交 LLM 做策略/回复客户）
        assertThat(turn.loopReply()).isEqualTo("请问您要查询哪个订单号？");
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test
    void loop_roundsExhausted_stopsWithAccumulatedErrors() throws Exception {
        FlakyHttpTool tool = new FlakyHttpTool(99, 500); // 恒 500
        Method m = FlakyHttpTool.class.getDeclaredMethod("call");
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(5, 30000, () -> 0);
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(
                toolCallResp(call("c1", "call", "{}")),
                toolCallResp(call("c2", "call", "{}"))));
        ToolCallExecutor executor = executorWith(model,
                Map.of("call", noRetryExecutor(tool, m, breaker)), List.of(spec), 2);

        ToolTurn turn = executor.execute("查外部数据");

        // 轮次耗尽：失败结果累计交终答 LLM 如实说明（不无限循环、不抛出）
        assertThat(turn.results()).hasSize(2);
        assertThat(turn.results()).allMatch(ToolCallResult::isError);
        assertThat(turn.loopReply()).isNull();
        assertThat(model.calls.get()).isEqualTo(2);
    }

    @Test
    void loop_hallucinatedToolName_errorResultFedBack_thenSelfCorrects() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method m = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(5, 30000, () -> 0);
        QueuedModelExecutor model = new QueuedModelExecutor(List.of(
                toolCallResp(call("c1", "noSuchTool", "{}")), // 幻觉工具名
                toolCallResp(call("c2", "triangleArea", "{\"base\":3,\"height\":4}"))));
        ToolCallExecutor executor = executorWith(model,
                Map.of("triangleArea", noRetryExecutor(tool, m, breaker)), List.of(spec), 2);

        ToolTurn turn = executor.execute("算三角形面积");

        assertThat(turn.results()).hasSize(2);
        assertThat(turn.results().get(0).error().kind()).isEqualTo(ToolErrorKind.UNKNOWN_TOOL);
        assertThat(turn.results().get(0).error().attempts()).isZero(); // 未执行
        assertThat(turn.results().get(1).content()).isEqualTo("6"); // 改选真工具成功
        assertThat(model.calls.get()).isEqualTo(2);
    }
}
