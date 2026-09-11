package com.agentdemo007.capability.tool;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.TokenBudgetChecker;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.gateway.llm.GatewayChatModel;
import com.agentdemo007.resilience.CircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证 {@link GatewayChatModel} 作 {@link AiServices} 的真 {@link dev.langchain4j.model.chat.ChatModel}
 * 驱动 function-calling 循环（② Slice 2c——集成收口）。镜像 {@code AiServicesToolLoopTest}，
 * 但把脚本化假 ChatModel 换成<b>网关背书</b>的 {@link GatewayChatModel}：每轮 {@code AiServices} 下发
 * {@code ChatRequest}（messages + tool-spec）→ {@link GatewayChatModel#doChat} 翻译成 {@code GatewayRequest}
 * 工具路径 → {@link UnifiedModelGateway}（预算/failover/熔断/关思考全栈韧性）→ 脚本化执行器 →
 * {@code LlmResponse(toolCalls)} → 翻译回 {@code ChatResponse} 交 {@code AiServices} 继续循环。
 *
 * <p>退役映射全坐实（经网关亦成立）：循环 = {@code AiServices}、派发+参数强转+反射调 =
 * {@link DefaultToolExecutor}、韧性 = {@link ResilientToolExecutor} 装饰、调模型 = {@link GatewayChatModel}
 * 经网关（非旁路）。终答含真 {@code @Tool} 算出的 "6" 即证全数据流经网关闭环。
 *
 * <p>关联 [[langchain4j-boot4-compat-findings]] [[dont-hardwrite-use-dep-methods]] [[phase4-gateway-design]]。
 */
class GatewayChatModelAiServicesLoopTest {

    /** AiServices 接口：单 String 入参→UserMessage，String 返回→AiServices 取终答文本。 */
    interface AreaAssistant {
        @UserMessage("{{q}}")
        String ask(@V("q") String question);
    }

    /**
     * 脚本化执行器（网关背书的"模型"）：call1 发起工具调用（content=null + toolCalls），
     * call2 回终答文本——证 {@link DefaultToolExecutor} 算出的 "6" 经 {@link GatewayChatModel}
     * 翻译回传进终答。计数 calls 证循环经网关跑满 2 轮。
     */
    static final class ScriptedGatewayExecutor implements ModelExecutor {
        private final String toolName;
        private final String toolArgs;
        private int calls = 0;

        ScriptedGatewayExecutor(String toolName, String toolArgs) {
            this.toolName = toolName;
            this.toolArgs = toolArgs;
        }

        @Override
        public LlmResponse execute(LlmRequest request) {
            calls++;
            if (calls == 1) {
                // call1：模型发起工具调用（GatewayChatModel 把 toolCalls 翻成 AiMessage.toolExecutionRequests 交 AiServices）
                ToolExecutionRequest req = ToolExecutionRequest.builder()
                        .name(toolName).arguments(toolArgs).build();
                return new LlmResponse(request.modelId(), null, 5, List.of(req));
            }
            // call2：终答文本（GatewayChatModel 翻成 STOP + text，AiServices 取为终答）
            return new LlmResponse(request.modelId(), "面积是 6", 10);
        }

        int calls() {
            return calls;
        }
    }

    private static FlowControlPolicy noLimit() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(60));
    }

    @Test
    void aiServicesLoop_throughGatewayChatModel_drivesRealTool_dataFlowCloses() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);

        // 韧性装饰：计数 + 捕获包裹 DefaultToolExecutor（证真派发原语执行真 @Tool 算出 6）
        AtomicInteger execCount = new AtomicInteger();
        AtomicReference<String> toolResult = new AtomicReference<>();
        DefaultToolExecutor defaultExec = new DefaultToolExecutor(tool, method);
        ToolExecutor counting = (r, mem) -> {
            execCount.incrementAndGet();
            String res = defaultExec.execute(r, mem);
            toolResult.set(res);
            return res;
        };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        ResilientToolExecutor resilient = new ResilientToolExecutor(counting, breaker);

        // 网关背书的 ChatModel：脚本化执行器（call1→tool_call, call2→终答）经真网关栈
        ScriptedGatewayExecutor exec = new ScriptedGatewayExecutor(spec.name(), "{\"base\":3,\"height\":4}");
        UnifiedModelGateway gateway = new UnifiedModelGateway(exec, new TokenBudgetChecker(),
                new com.agentdemo007.gateway.core.FailoverExecutor());
        GatewayChatModel chatModel = new GatewayChatModel(gateway, "siliconflow-large", 512,
                FailoverPolicy.builder("fo").build(), noLimit(), true);

        AreaAssistant assistant = AiServices.builder(AreaAssistant.class)
                .chatModel(chatModel)
                .tools(Map.of(spec, resilient))
                .build();

        String answer = assistant.ask("底3高4的三角形面积");

        // 终答含工具算出的 6（全数据流经网关闭环——GatewayChatModel 双向翻译 + AiServices 循环 + 真工具执行）
        assertThat(answer).contains("6");
        // DefaultToolExecutor 执行一次真 @Tool（经 AiServices 循环派发，非手撸）
        assertThat(execCount.get()).isEqualTo(1);
        assertThat(toolResult.get()).isEqualTo("6"); // JSON 参数强转 + 反射调真方法算出 6
        // 循环经网关跑满 2 轮（tool_call → 终答），每轮 doChat 翻译 round-trip
        assertThat(exec.calls()).isEqualTo(2);
        // 韧性记账不跳闸（ResilientToolExecutor 装饰 DefaultToolExecutor，工具成功→recordSuccess）
        assertThat(breaker.state(spec.name())).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
