package com.agentdemo007.gateway.core;

import com.agentdemo007.capability.tool.TriangleAreaTool;
import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证网关工具调用路径的数据管线（② Slice 2a）：{@link UnifiedModelGateway#invoke} 接受
 * 工具路径 {@link GatewayRequest}（messages + tools）后，{@link FailoverExecutor} 把 messages/tools
 * 原样透传进 {@link LlmRequest}（不丢、不改模型候选/failover 语义）。
 *
 * <p>前置 {@code LangChain4jModelExecutor} 已工具感知（Slice 1，messages 非空→用 messages + toolSpecifications）；
 * 本测试只证「网关层把 messages/tools 透到执行器入参」这一段，stub 执行器捕获 LlmRequest 即断言。
 *
 * <p>plain-chat 路径（prompt 单串）不受影响——compat ctor 仍产 messages/tools 空 List，执行器回退 prompt。
 * 关联 [[langchain4j-boot4-compat-findings]] [[phase4-gateway-design]]。
 */
class GatewayToolPathTest {

    /** 捕获执行器：记下收到的 LlmRequest（证透传），返回占位响应（invoke 需非空返回以过记账）。 */
    static final class CapturingExecutor implements ModelExecutor {
        LlmRequest captured;

        @Override
        public LlmResponse execute(LlmRequest request) {
            this.captured = request;
            return new LlmResponse(request.modelId(), "ok", 1);
        }
    }

    private static FlowControlPolicy noLimit() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(60));
    }

    @Test
    void invoke_toolPathGatewayRequest_forwardsMessagesAndToolsToExecutorLlmRequest() throws Exception {
        CapturingExecutor exec = new CapturingExecutor();
        // 真网关栈：真预算关卡（noLimit 放行）+ 真 failover（noRetry，主=唯一候选）+ stub 执行器捕获入参
        UnifiedModelGateway gateway = new UnifiedModelGateway(exec, new TokenBudgetChecker(), new FailoverExecutor());

        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
        List<ChatMessage> messages = List.of(new UserMessage("底3高4的三角形面积"));

        GatewayRequest req = new GatewayRequest("siliconflow-large", messages, List.of(spec),
                512, FailoverPolicy.builder("fo").build(), noLimit(), true);

        gateway.invoke(req); // 预算关卡 → failover（主=唯一候选）→ 执行器 → 记账

        // messages/tools 透传：执行器收到的 LlmRequest 带 messages + tools（非空，未被网关层吞掉）
        assertThat(exec.captured).isNotNull();
        assertThat(exec.captured.messages()).hasSize(1);
        assertThat(exec.captured.tools()).hasSize(1);
        assertThat(exec.captured.tools().get(0).name()).isEqualTo(spec.name());
        // tool-path 时 prompt 为空（messages 承内容）；modelId/disableThinking 原样透传
        assertThat(exec.captured.prompt()).isNull();
        assertThat(exec.captured.modelId()).isEqualTo("siliconflow-large");
        assertThat(exec.captured.disableThinking()).isTrue();
    }
}
