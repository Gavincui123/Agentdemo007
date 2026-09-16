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
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 证 {@link ToolCallExecutor}（② Slice 3·option A 数据步·替手撸 ToolExecutor 角色）：单次前向
 * {@link GatewayChatModel#doChat}（messages=[query] + tools=specs）→ 模型出 {@code tool_calls} 则经
 * {@link ResilientToolExecutor}（包 {@link DefaultToolExecutor}）执行真 {@code @Tool} → 返回结构化结果
 * （{@link ToolCallResult}，带 category，[[business-tools-workflow-dag]] §2.2）；无 {@code tool_calls} → 空。
 * breaker OPEN → 透传 {@link ToolCircuitOpenException}（交 {@code ToolExecutionStep} 收口 {@code TOOL_FAILURE}）。
 *
 * <p>退役手撸 Detector/ParamParser/SchemaValidator/Reparser：模型直接出结构化 {@code tool_calls}（含 JSON 参数），
 * 无关键词检测/手解析/手校验/重解析循环——schema 由 {@code @Tool} 注解经
 * {@link ToolSpecifications#toolSpecificationFrom} 生成，参数强转+反射调归 {@link DefaultToolExecutor}
 * （[[dont-hardwrite-use-dep-methods]]：库方法优先；[[langchain4j-boot4-compat-findings]]：seam=委托非重写）。
 *
 * <p>seam：{@link ToolCallExecutor#buildChatModel()} prod 按 CHIT_CHAT 路由（小模型+关思考，工具探测=决策调用，
 * 镜像 {@code ChatLlmService.decide}）从 ModelConfigCenter 解析 primary/failover/flow；测试覆写为 scripted
 * （镜像 {@code GatewayChatModelAiServicesLoopTest} 的 ScriptedGatewayExecutor：真网关栈 + 脚本化执行器出罐装
 * tool_calls），证 dispatch 逻辑免 ModelConfigCenter 装配耦合。工具探测不驱动循环（区别 AiServices 自驱动
 * agent 模式）——单次前向出结果作数据，pipeline 尾零改（[[degradation-and-eval-principles]]：收口最重要）。
 */
class ToolCallExecutorTest {

    /** 脚本化执行器：返回罐装 LlmResponse（出 tool_calls 或纯文本），证真网关栈经 GatewayChatModel 翻译。 */
    static final class ScriptedModelExecutor implements ModelExecutor {
        private final LlmResponse canned;

        ScriptedModelExecutor(LlmResponse canned) {
            this.canned = canned;
        }

        @Override
        public LlmResponse execute(LlmRequest request) {
            return canned;
        }
    }

    private static FlowControlPolicy noLimit() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(60));
    }

    private static ToolSpecification triangleSpec() throws Exception {
        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        return ToolSpecifications.toolSpecificationFrom(method);
    }

    /** 建 ToolCallExecutor（buildChatModel 覆写为 scripted gateway 出 canned 响应）。 */
    private ToolCallExecutor executorWith(LlmResponse canned, ToolCircuitBreaker breaker, ToolSpecification spec,
                                          TriangleAreaTool tool) throws Exception {
        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        UnifiedModelGateway gateway = new UnifiedModelGateway(
                new ScriptedModelExecutor(canned), new TokenBudgetChecker(),
                new com.agentdemo007.gateway.core.FailoverExecutor());
        // 韧性装饰：ResilientToolExecutor 包 DefaultToolExecutor（证真派发原语执行真 @Tool 算出 6）
        ToolExecutor resilient = new ResilientToolExecutor(new DefaultToolExecutor(tool, method), breaker);
        // categoryMap 空 → triangleArea 经 getOrDefault 默认 COMPUTE（[[business-tools-workflow-dag]] §2.2）
        return new ToolCallExecutor(null, null, 512, List.of(spec), Map.of(spec.name(), resilient), Map.of()) {
            @Override
            protected GatewayChatModel buildChatModel() {
                return new GatewayChatModel(gateway, "test-small", 512,
                        FailoverPolicy.builder("fo").build(), noLimit(), true);
            }
        };
    }

    @Test
    void execute_toolCall_dispatchesRealTool_returnsResults() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        ToolSpecification spec = triangleSpec();
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .name(spec.name()).arguments("{\"base\":3,\"height\":4}").build();
        LlmResponse canned = new LlmResponse("test-small", null, 5, List.of(call));

        List<ToolCallResult> results = executorWith(canned, breaker, spec, tool).execute("底3高4的三角形面积");

        // 真派发原语（DefaultToolExecutor）+ 真 @Tool 算出 6，经网关栈单次前向
        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("6");
        // triangleArea 无 @ToolChannel → COMPUTE 默认（[[business-tools-workflow-dag]] §2.2）
        assertThat(results.get(0).category()).isEqualTo(ToolCategory.COMPUTE);
        // 韧性记账不跳闸（工具成功 → recordSuccess）
        assertThat(breaker.state(spec.name())).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void execute_noToolCall_returnsEmpty() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        ToolSpecification spec = triangleSpec();
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        // 模型纯文本回复（无 tool_calls）→ 无工具触发 → 空（交下游正常对话）
        LlmResponse canned = new LlmResponse("test-small", "这个问题不需要工具", 8, List.of());

        List<ToolCallResult> results = executorWith(canned, breaker, spec, tool).execute("你好");

        assertThat(results).isEmpty();
    }

    @Test
    void execute_breakerOpen_propagatesToolCircuitOpenException() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        ToolSpecification spec = triangleSpec();
        // threshold=1：一次 recordFailure 即 OPEN
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        breaker.recordFailure(spec.name()); // 预开
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .name(spec.name()).arguments("{\"base\":3,\"height\":4}").build();
        LlmResponse canned = new LlmResponse("test-small", null, 5, List.of(call));

        // breaker OPEN → ResilientToolExecutor 快速失败抛 ToolCircuitOpenException → 透传交 ToolExecutionStep 收口
        assertThatThrownBy(() -> executorWith(canned, breaker, spec, tool).execute("底3高4的三角形面积"))
                .isInstanceOf(ToolCircuitOpenException.class)
                .hasMessageContaining(spec.name());
    }

    @Test
    void execute_noChatRoute_returnsEmpty_gracefulDegrade() throws Exception {
        // dev/未装配：center 未 refresh（current=null）→ routeFor(CHIT_CHAT) 空 → buildChatModel 返 null
        // → execute 降级返空（不抛、不阻塞主链路，交下游正常对话）
        TriangleAreaTool tool = new TriangleAreaTool();
        ToolSpecification spec = triangleSpec();
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        Method method = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolExecutor resilient = new ResilientToolExecutor(new DefaultToolExecutor(tool, method), breaker);
        ModelConfigCenter emptyCenter = new ModelConfigCenter(() -> null, new ModelRegistry());
        ToolCallExecutor executor = new ToolCallExecutor(null, emptyCenter, 512,
                List.of(spec), Map.of(spec.name(), resilient), Map.of());

        List<ToolCallResult> results = executor.execute("底3高4的三角形面积");

        assertThat(results).isEmpty();
    }
}
