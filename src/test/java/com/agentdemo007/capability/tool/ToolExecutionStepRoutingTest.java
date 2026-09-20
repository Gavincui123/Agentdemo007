package com.agentdemo007.capability.tool;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.agentdemo007.capability.tool.ToolCategory.COMPUTE;
import static com.agentdemo007.capability.tool.ToolCategory.RAG;
import static com.agentdemo007.capability.tool.ToolCategory.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolExecutionStep} 通道路由单测（[[business-tools-workflow-dag]] §2.2·用户钦定 + 2026-09-17 错误通道扩展）。
 *
 * <p>验 {@link ToolCallExecutor#execute} 返回 {@link ToolTurn} 后，{@link ToolExecutionStep#process}
 * 按结果性质路由：
 * <ul>
 *   <li>失败结果（{@link ToolCallResult#isError()}）→ {@code context.toolErrors}（独立错误通道，
 *       不混 runtimeFacts 高置信事实）；</li>
 *   <li>RUNTIME → {@code context.runtimeFacts}（高置信外部系统事实，进 System 锚点层 Runtime 块）；</li>
 *   <li>RAG → 拆 JSON {@code {text,source}} → {@code ragFragments}+{@code ragCitations}（带 citation）；</li>
 *   <li>COMPUTE → {@code toolResults}（简单计算，现状不变）；</li>
 *   <li>RAG 畸形 JSON → 兜底 raw 入 ragFragments、citation 空（②每步降级，不阻塞）；</li>
 *   <li>模型澄清话术（{@link ToolTurn#loopReply()}）→ {@code context.toolLoopReply}。</li>
 * </ul>
 * scripted executor 覆写 {@code execute} 注入罐装 {@link ToolTurn}（免真模型，证路由逻辑）。
 */
class ToolExecutionStepRoutingTest {

    /** scripted 执行器：覆写 execute 返罐装 ToolTurn（证路由逻辑，免 ModelConfigCenter 装配）。 */
    private static ToolCallExecutor scripted(ToolTurn canned) {
        return new ToolCallExecutor(null, null, 0, List.of(), Map.of(), Map.of()) {
            @Override
            public ToolTurn execute(String query) {
                return canned;
            }
        };
    }

    private static ToolCallResult success(String name, String content, ToolCategory category) {
        return new ToolCallResult(name, content, category);
    }

    private static ToolCallResult failure(String name, ToolErrorKind kind, ToolCategory category) {
        return new ToolCallResult(name,
                new ToolError(kind, "失败原因", 2).toText(name), category,
                new ToolError(kind, "失败原因", 2));
    }

    private static PipelineContext ctx() {
        return new PipelineContext("s1", "查一下订单 ORD-001");
    }

    @Test
    void runtimeResult_routedToRuntimeFacts() {
        ToolCallResult r = success("queryOrder", "订单 ORD-001 已签收", RUNTIME);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(r), null)));
        PipelineContext c = ctx();

        StepOutcome outcome = step.process(c);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(c.runtimeFacts()).containsExactly("订单 ORD-001 已签收");
        assertThat(c.toolResults()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
        assertThat(c.ragCitations()).isEmpty();
        assertThat(c.toolErrors()).isEmpty();
    }

    @Test
    void ragResult_splitToFragmentsAndCitations() {
        String json = "{\"text\":\"7天无理由退货\",\"source\":\"退货政策知识库§3\"}";
        ToolCallResult r = success("queryReturnPolicy", json, RAG);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(r), null)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.ragFragments()).containsExactly("7天无理由退货");
        assertThat(c.ragCitations()).containsExactly("退货政策知识库§3");
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.toolResults()).isEmpty();
    }

    @Test
    void computeResult_routedToToolResults() {
        ToolCallResult r = success("triangleArea", "6", COMPUTE);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(r), null)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.toolResults()).containsExactly("6");
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
    }

    @Test
    void mixedResults_eachRoutedToOwnChannel() {
        ToolCallResult rt = success("queryOrder", "ORD-001 已签收", RUNTIME);
        ToolCallResult rag = success("queryReturnPolicy",
                "{\"text\":\"7天\",\"source\":\"退货§3\"}", RAG);
        ToolCallResult comp = success("triangleArea", "6", COMPUTE);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(rt, rag, comp), null)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.runtimeFacts()).containsExactly("ORD-001 已签收");
        assertThat(c.ragFragments()).containsExactly("7天");
        assertThat(c.ragCitations()).containsExactly("退货§3");
        assertThat(c.toolResults()).containsExactly("6");
    }

    @Test
    void ragResult_malformedJson_fallsBackToRagFragmentsRaw_emptyCitation() {
        // ②每步降级：畸形 JSON 不阻塞，raw 入 ragFragments、citation 空
        ToolCallResult r = success("queryReturnPolicy", "不是JSON的纯文本政策", RAG);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(r), null)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.ragFragments()).containsExactly("不是JSON的纯文本政策");
        assertThat(c.ragCitations()).isEmpty();
    }

    @Test
    void errorResult_routedToToolErrors_neverIntoRuntimeFacts() {
        // 失败结果（RUNTIME 通道）→ toolErrors 独立通道：错误不是事实，不得冒充高置信外部数据
        ToolCallResult err = failure("queryOrder", ToolErrorKind.TIMEOUT, RUNTIME);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(err), null)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.toolErrors()).containsExactly(err);
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
        assertThat(c.toolResults()).isEmpty();
    }

    @Test
    void mixedSuccessAndError_errorGoesToErrorChannel_successToOwnChannel() {
        ToolCallResult ok = success("queryUser", "用户 10086", RUNTIME);
        ToolCallResult err = failure("queryOrder", ToolErrorKind.HTTP_5XX, RUNTIME);
        ToolExecutionStep step = new ToolExecutionStep(scripted(new ToolTurn(List.of(ok, err), null)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.runtimeFacts()).containsExactly("用户 10086"); // 成功事实照常
        assertThat(c.toolErrors()).containsExactly(err);            // 失败单独收口
    }

    @Test
    void loopReply_writtenToContext() {
        ToolExecutionStep step = new ToolExecutionStep(scripted(
                new ToolTurn(List.of(failure("queryOrder", ToolErrorKind.PARAM_INVALID, RUNTIME)),
                        "请问您要查询哪个订单号？")));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.toolLoopReply()).isEqualTo("请问您要查询哪个订单号？");
        assertThat(c.toolErrors()).hasSize(1);
    }

    @Test
    void emptyTurn_proceedsAllChannelsEmpty() {
        ToolExecutionStep step = new ToolExecutionStep(scripted(ToolTurn.empty()));
        PipelineContext c = ctx();

        StepOutcome outcome = step.process(c);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
        assertThat(c.toolResults()).isEmpty();
        assertThat(c.toolErrors()).isEmpty();
        assertThat(c.toolLoopReply()).isNull();
    }
}
