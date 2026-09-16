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
 * {@link ToolExecutionStep} 3 通道路由单测（Slice 2·[[business-tools-workflow-dag]] §2.2·用户钦定）。
 *
 * <p>验 {@link ToolCallExecutor#execute} 返回结构化 {@link ToolCallResult}（带 category）后，
 * {@link ToolExecutionStep#process} 按 category 路由（替现状全量塞 toolResults）：
 * <ul>
 *   <li>RUNTIME → {@code context.runtimeFacts}（高置信外部系统事实，进 System 锚点层 Runtime 块）；</li>
 *   <li>RAG → 拆 JSON {@code {text,source}} → {@code ragFragments}+{@code ragCitations}（带 citation）；</li>
 *   <li>COMPUTE → {@code toolResults}（简单计算，现状不变）；</li>
 *   <li>RAG 畸形 JSON → 兜底 raw 入 ragFragments、citation 空（②每步降级，不阻塞）。</li>
 * </ul>
 * scripted executor 覆写 {@code execute} 注入罐装 {@link ToolCallResult}（免真模型，证路由逻辑）；
 * 4 组真 SiliconFlow 工具调用语义验于 Slice 5（[[business-tools-workflow-dag]] §2.5）。
 */
class ToolExecutionStepRoutingTest {

    /** scripted 执行器：覆写 execute 返罐装 ToolCallResult（证路由逻辑，免 ModelConfigCenter 装配）。 */
    private static ToolCallExecutor scripted(List<ToolCallResult> canned) {
        return new ToolCallExecutor(null, null, 0, List.of(), Map.of(), Map.of()) {
            @Override
            public List<ToolCallResult> execute(String query) {
                return canned;
            }
        };
    }

    private static PipelineContext ctx() {
        return new PipelineContext("s1", "查一下订单 ORD-001");
    }

    @Test
    void runtimeResult_routedToRuntimeFacts() {
        ToolCallResult r = new ToolCallResult("queryOrder", "订单 ORD-001 已签收", RUNTIME);
        ToolExecutionStep step = new ToolExecutionStep(scripted(List.of(r)));
        PipelineContext c = ctx();

        StepOutcome outcome = step.process(c);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(c.runtimeFacts()).containsExactly("订单 ORD-001 已签收");
        assertThat(c.toolResults()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
        assertThat(c.ragCitations()).isEmpty();
    }

    @Test
    void ragResult_splitToFragmentsAndCitations() {
        String json = "{\"text\":\"7天无理由退货\",\"source\":\"退货政策知识库§3\"}";
        ToolCallResult r = new ToolCallResult("queryReturnPolicy", json, RAG);
        ToolExecutionStep step = new ToolExecutionStep(scripted(List.of(r)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.ragFragments()).containsExactly("7天无理由退货");
        assertThat(c.ragCitations()).containsExactly("退货政策知识库§3");
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.toolResults()).isEmpty();
    }

    @Test
    void computeResult_routedToToolResults() {
        ToolCallResult r = new ToolCallResult("triangleArea", "6", COMPUTE);
        ToolExecutionStep step = new ToolExecutionStep(scripted(List.of(r)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.toolResults()).containsExactly("6");
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
    }

    @Test
    void mixedResults_eachRoutedToOwnChannel() {
        ToolCallResult rt = new ToolCallResult("queryOrder", "ORD-001 已签收", RUNTIME);
        ToolCallResult rag = new ToolCallResult("queryReturnPolicy",
                "{\"text\":\"7天\",\"source\":\"退货§3\"}", RAG);
        ToolCallResult comp = new ToolCallResult("triangleArea", "6", COMPUTE);
        ToolExecutionStep step = new ToolExecutionStep(scripted(List.of(rt, rag, comp)));
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
        ToolCallResult r = new ToolCallResult("queryReturnPolicy", "不是JSON的纯文本政策", RAG);
        ToolExecutionStep step = new ToolExecutionStep(scripted(List.of(r)));
        PipelineContext c = ctx();

        step.process(c);

        assertThat(c.ragFragments()).containsExactly("不是JSON的纯文本政策");
        assertThat(c.ragCitations()).isEmpty();
    }

    @Test
    void emptyResults_proceedsAllChannelsEmpty() {
        ToolExecutionStep step = new ToolExecutionStep(scripted(List.of()));
        PipelineContext c = ctx();

        StepOutcome outcome = step.process(c);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(c.runtimeFacts()).isEmpty();
        assertThat(c.ragFragments()).isEmpty();
        assertThat(c.toolResults()).isEmpty();
    }
}
