package com.agentdemo007.gateway.llm;

import com.agentdemo007.capability.tool.TriangleAreaTool;
import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.TokenBudgetChecker;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证 {@link GatewayChatModel}（② Slice 2b）：把 LC4j {@link ChatRequest}（messages + tools，{@code AiServices}
 * 循环每轮下发）翻译成 {@code GatewayRequest} 工具路径经 {@link UnifiedModelGateway} 出站，再把
 * {@link LlmResponse}（含 {@code toolCalls}）翻成 {@link ChatResponse} 交回 {@code AiServices}。
 *
 * <p>双向翻译契约：
 * <ul>
 *   <li>IN：ChatRequest.messages()/toolSpecifications() → GatewayRequest 工具路径（messages+tools 透传到执行器入参）</li>
 *   <li>OUT：LlmResponse.toolCalls → ChatResponse.aiMessage().toolExecutionRequests()；content→text；tokens→tokenUsage</li>
 *   <li>finishReason：有 tool_calls → {@link FinishReason#TOOL_EXECUTION}；纯文本 → {@link FinishReason#STOP}</li>
 * </ul>
 *
 * <p>真网关栈（stub 执行器返回罐装 tool_calls 响应）：预算关卡（noLimit 放行）+ failover（noRetry）——
 * 证工具路径全程经网关韧性，非旁路。关联 [[langchain4j-boot4-compat-findings]] [[phase4-gateway-design]]。
 */
class GatewayChatModelTest {

    /** 捕获执行器：记下 LlmRequest（证 GatewayChatModel 建了工具路径 GatewayRequest），返回罐装 tool_calls 响应。 */
    static final class ToolCallExecutor implements ModelExecutor {
        LlmRequest captured;
        private final ToolExecutionRequest toolCall;

        ToolCallExecutor(ToolExecutionRequest toolCall) {
            this.toolCall = toolCall;
        }

        @Override
        public LlmResponse execute(LlmRequest request) {
            this.captured = request;
            return new LlmResponse(request.modelId(), null, 15, List.of(toolCall));
        }
    }

    private static FlowControlPolicy noLimit() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(60));
    }

    @Test
    void doChat_translatesToolRequest_returnsToolCallsInChatResponse() throws Exception {
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .name("triangleArea").arguments("{\"base\":3,\"height\":4}").build();
        ToolCallExecutor exec = new ToolCallExecutor(toolCall);
        UnifiedModelGateway gateway = new UnifiedModelGateway(exec, new TokenBudgetChecker(), new com.agentdemo007.gateway.core.FailoverExecutor());

        GatewayChatModel model = new GatewayChatModel(gateway, "siliconflow-large", 512,
                FailoverPolicy.builder("fo").build(), noLimit(), true);

        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(new UserMessage("底3高4的三角形面积"))
                .toolSpecifications(spec)
                .build();

        ChatResponse resp = model.doChat(chatRequest);

        // IN 证：GatewayChatModel 把 ChatRequest 的 messages/tools 翻成工具路径 GatewayRequest → 透传到执行器入参
        assertThat(exec.captured).isNotNull();
        assertThat(exec.captured.messages()).hasSize(1);
        assertThat(exec.captured.tools()).hasSize(1);
        assertThat(exec.captured.tools().get(0).name()).isEqualTo("triangleArea");
        // OUT 证：LlmResponse.toolCalls → ChatResponse.aiMessage().toolExecutionRequests()
        assertThat(resp.aiMessage().toolExecutionRequests()).hasSize(1);
        assertThat(resp.aiMessage().toolExecutionRequests().get(0).name()).isEqualTo("triangleArea");
        assertThat(resp.aiMessage().toolExecutionRequests().get(0).arguments()).contains("3").contains("4");
        // tokens 透传（tokenUsage.totalTokenCount）
        assertThat(resp.tokenUsage().totalTokenCount()).isEqualTo(15);
        // finishReason：模型发起工具调用 → TOOL_EXECUTION
        assertThat(resp.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
    }

    @Test
    void doChat_plainTextResponse_mapsToStopFinishReason() {
        // 模型纯文本回复（无 tool_calls）→ finishReason=STOP，text=content
        ModelExecutor textExec = request -> new LlmResponse(request.modelId(), "面积是 6", 12);
        UnifiedModelGateway gateway = new UnifiedModelGateway(textExec, new TokenBudgetChecker(),
                new com.agentdemo007.gateway.core.FailoverExecutor());
        GatewayChatModel model = new GatewayChatModel(gateway, "siliconflow-large", 512,
                FailoverPolicy.builder("fo").build(), noLimit(), true);

        ChatResponse resp = model.doChat(ChatRequest.builder()
                .messages(new UserMessage("底3高4的三角形面积"))
                .build());

        assertThat(resp.aiMessage().text()).isEqualTo("面积是 6");
        assertThat(resp.aiMessage().toolExecutionRequests()).isEmpty();
        assertThat(resp.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(resp.tokenUsage().totalTokenCount()).isEqualTo(12);
    }
}
