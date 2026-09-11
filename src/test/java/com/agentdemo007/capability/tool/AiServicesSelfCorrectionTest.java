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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证 LC4j 原生自纠正退役手撸 {@code Reparser} + {@code ToolErrorFeedback} + {@code ToolExecutor.executeCall} 递归
 * （[[dont-hardwrite-use-dep-methods]]：库方法优先；[[langchain4j-boot4-compat-findings]]：seam=委托非重写）。
 *
 * <p><b>实测发现（铁律②观察颠覆假设）</b>：{@link DefaultToolExecutor} 原生捕获 {@code @Tool} 方法抛出的异常，
 * 把<b>异常消息作为工具结果</b>返回（不抛出）→ 喂回模型 → 模型在 AiServices 循环里自己重发修正参数自纠正。
 * 故 Reparser（LLM 重解析修正调用）= 模型自纠正退役；ToolErrorFeedback（异常→反馈 prompt）= DefaultToolExecutor
 * 默认捕获退役；executeCall 递归 = AiServices 循环退役。<b>连 {@code ToolExecutionErrorHandler} 都不必设</b>
 * ——它是可选"定制反馈文本"seam（{@code .toolExecutionErrorHandler()}），默认路径不走它。
 *
 * <p><b>语义迁移（须记）</b>：{@link ResilientToolExecutor} 只在执行器<b>抛出</b>时记失败；
 * 而 DefaultToolExecutor 把业务异常（坏参）吞成结果返回→ResilientToolExecutor 见成功返回→记成功。
 * 故 breaker 现在只对 <b>infra 失败</b>（执行器自身抛出：反射/类错误等）跳闸，不对业务异常（坏参）跳闸
 * ——与手撸 ToolExecutor（对 ToolRecoverableException 记失败）不同。本地 @Tool 无 HTTP，infra 失败罕见，
 * 熔断在 LC4j 路径主要防执行器级故障。
 *
 * <p>脚本化假 ChatModel：call1 坏参(base=-1)→工具抛→DefaultToolExecutor 吞成"底/高不可为负"返回→
 * 喂回模型→call2 重发好参→成功→call3 回声"6"。终答含 6 即证全闭环自纠正，零手撸 Reparser。
 */
class AiServicesSelfCorrectionTest {

    interface AreaAssistant {
        @UserMessage("{{q}}")
        String ask(@V("q") String question);
    }

    /**
     * 脚本化自纠正 ChatModel：call1 坏参（触发工具异常→DefaultToolExecutor 吞成结果喂回），
     * call2 好参（模型自纠正重发），call3 回声工具结果。双入口 chat/doChat 消除调用点不确定性。
     */
    static final class SelfCorrectingChatModel implements ChatModel {
        private final String toolName;
        private int calls = 0;

        SelfCorrectingChatModel(String toolName) {
            this.toolName = toolName;
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
                // 故意坏参：base=-1 → TriangleAreaTool 抛 ToolRecoverableException
                // → DefaultToolExecutor 原生捕获，以"底/高不可为负"为工具结果返回（不抛出）
                return toolCall(toolName, "{\"base\":-1,\"height\":4}");
            }
            if (calls == 2) {
                // 模型收到错误反馈后自纠正，重发正确参
                return toolCall(toolName, "{\"base\":3,\"height\":4}");
            }
            // call3：回声工具执行结果——证好参算出的 "6" 经循环回传进终答
            String toolResult = chatRequest.messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .map(ToolExecutionResultMessage::text)
                    .reduce((a, b) -> b)
                    .orElse("NO_TOOL_RESULT");
            return ChatResponse.builder()
                    .aiMessage(AiMessage.builder().text("面积是 " + toolResult).build())
                    .finishReason(FinishReason.STOP)
                    .build();
        }

        private ChatResponse toolCall(String name, String args) {
            ToolExecutionRequest tr = ToolExecutionRequest.builder().name(name).arguments(args).build();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.builder().toolExecutionRequests(List.of(tr)).build())
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build();
        }

        int calls() {
            return calls;
        }
    }

    @Test
    void defaultToolExecutor_selfCorrectsByReturningErrorAsResult_retiresHandRolledReparser() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);

        AtomicInteger execCount = new AtomicInteger();
        DefaultToolExecutor defaultExec = new DefaultToolExecutor(tool, method);
        ToolExecutor counting = (r, mem) -> {
            execCount.incrementAndGet();
            return defaultExec.execute(r, mem);
        };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(5, 30000, () -> 0); // 阈值5：宽放，证不跳闸
        ResilientToolExecutor resilient = new ResilientToolExecutor(counting, breaker);

        SelfCorrectingChatModel fake = new SelfCorrectingChatModel(spec.name());
        AreaAssistant assistant = AiServices.builder(AreaAssistant.class)
                .chatModel(fake)
                .tools(Map.of(spec, resilient))
                .maxToolCallingRoundTrips(5)
                .build();

        String answer = assistant.ask("底-1高4的三角形面积");

        // 终答含 6 = 自纠正全闭环：坏参→工具异常被 DefaultToolExecutor 吞成结果喂回→模型重发好参→"6"回传
        assertThat(answer).contains("6");
        // 工具执行 2 次（坏参 + 好参）——模型自纠正重发，无手撸 Reparser.reparse
        assertThat(execCount.get()).isEqualTo(2);
        // 循环 3 轮（坏call→好call→终答）——无手撸 executeCall 递归
        assertThat(fake.calls()).isEqualTo(3);
        // 业务异常被 DefaultToolExecutor 吞成结果（未抛出）→ ResilientToolExecutor 见成功返回→记成功不跳闸
        // （语义迁移：breaker 只对 infra 失败跳闸，见类 javadoc）
        assertThat(breaker.state(spec.name())).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
