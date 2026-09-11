package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.CircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证 {@link AiServices} 当家 function-calling 循环 + {@link ResilientToolExecutor} 装饰
 * {@link DefaultToolExecutor}，零手撸派发器/参数解析/循环/回传
 * （[[dont-hardwrite-use-dep-methods]]：库方法优先；[[langchain4j-boot4-compat-findings]]：seam=委托非重写）。
 *
 * <p>退役映射坐实：循环 = {@code AiServices}（{@code maxToolCallingRoundTrips}）、
 * 派发 + 参数 JSON→double 强转 + 反射调 = {@link DefaultToolExecutor}、
 * 工具结果回传载体 = {@link ToolExecutionResultMessage}、韧性 = {@link ResilientToolExecutor} 装饰。
 *
 * <p>脚本化假 {@link ChatModel} 驱动（call1 出 tool_call、call2 回声工具执行结果），无真实 LLM、无文档依赖——
 * 纯 LC4j 原语组合验证。终答含真 {@code @Tool} 算出的 "6" 即证全数据流闭环。
 */
class AiServicesToolLoopTest {

    /** AiServices 接口：单 String 入参→UserMessage，String 返回→AiServices 取终答文本。 */
    interface AreaAssistant {
        @UserMessage("{{q}}")
        String ask(@V("q") String question);
    }

    /**
     * 脚本化 ChatModel（测试桩，已核实 LC4j 三 jar 无现成 fake，手写合规）：
     * call1 发起工具调用，call2 回声工具执行结果——证 {@link DefaultToolExecutor} 算出的 "6"
     * 经 {@link AiServices} 循环回传进终答。同时覆写 {@code chat}/{@code doChat} 双入口消除调用点不确定性。
     */
    static final class ScriptedChatModel implements ChatModel {
        private final String toolName;
        private final String toolArgs;
        private int calls = 0;

        ScriptedChatModel(String toolName, String toolArgs) {
            this.toolName = toolName;
            this.toolArgs = toolArgs;
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            return scripted(chatRequest);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return scripted(chatRequest);
        }

        private ChatResponse scripted(ChatRequest chatRequest) {
            calls++;
            if (calls == 1) {
                ToolExecutionRequest req = ToolExecutionRequest.builder()
                        .name(toolName).arguments(toolArgs).build();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.builder().toolExecutionRequests(List.of(req)).build())
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build();
            }
            // call2：回声工具执行结果——证 DefaultToolExecutor 算出的 "6" 经循环回传进终答
            String toolResult = chatRequest.messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .map(ToolExecutionResultMessage::text)
                    .reduce((a, b) -> b) // 取最后一条工具结果
                    .orElse("NO_TOOL_RESULT");
            return ChatResponse.builder()
                    .aiMessage(AiMessage.builder().text("面积是 " + toolResult).build())
                    .finishReason(FinishReason.STOP)
                    .build();
        }

        int calls() {
            return calls;
        }
    }

    @Test
    void aiServicesLoop_drivesRealToolViaDefaultExecutor_resilienceDecorates_noHandRolledDispatch() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);

        // 计数 + 捕获包裹 DefaultToolExecutor：证真派发原语被执行且算出正确结果（非手撸派发）
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

        ScriptedChatModel fake = new ScriptedChatModel(spec.name(), "{\"base\":3,\"height\":4}");
        AreaAssistant assistant = AiServices.builder(AreaAssistant.class)
                .chatModel(fake)
                .tools(Map.of(spec, resilient))
                .build();

        String answer = assistant.ask("底3高4的三角形面积");

        assertThat(answer).contains("6"); // 终答含工具算出的 6（数据流闭环）
        assertThat(execCount.get()).isEqualTo(1); // DefaultToolExecutor 执行一次真 @Tool
        assertThat(toolResult.get()).isEqualTo("6"); // JSON 参数强转 + 反射调真方法算出 6（非手撸派发）
        assertThat(fake.calls()).isEqualTo(2); // AiServices 循环 2 轮（tool_call → 终答）
        assertThat(breaker.state(spec.name())).isEqualTo(CircuitBreaker.State.CLOSED); // 韧性记账不跳闸
    }
}
