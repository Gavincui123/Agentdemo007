package com.agentdemo007.capability.rag;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagStep 拒答信号置位单测（[[refusal-design]]）：漏斗终态零片段（空召回/终闸不达标/链路异常）
 * → {@code context.groundingMiss=true}；命中注入 → 照常注入且不置位；闲聊免 RAG → 不置位。
 */
class RagStepGroundingMissTest {

    @Test
    void emptyRecallSetsGroundingMiss() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        RagStep step = new RagStep(retriever, new RetrievalValidator(0.3, 1, 0.3),
                mock(Reranker.class), new RagInjectionScanner(), 24, 10, 0.2);

        PipelineContext ctx = new PipelineContext("s1", "退款政策是什么？");
        ctx.setIntent(com.agentdemo007.intent.Intent.OTHER);
        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Degrade.class);
        assertThat(ctx.groundingMiss()).isTrue();
        assertThat(ctx.ragFragments()).isEmpty();
    }

    @Test
    void lowConfidencePoolSetsGroundingMiss() {
        Retriever retriever = mock(Retriever.class);
        // 余弦 0.1 < 终闸 0.3 → 全灭
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new RagFragment("不相关片段", 0.1, "src", null, null, null, null, null, true)));
        RagStep step = new RagStep(retriever, new RetrievalValidator(0.3, 1, 0.3),
                mock(Reranker.class), new RagInjectionScanner(), 24, 10, 0.2);

        PipelineContext ctx = new PipelineContext("s1", "退款政策是什么？");
        ctx.setIntent(com.agentdemo007.intent.Intent.OTHER);
        step.process(ctx);

        assertThat(ctx.groundingMiss()).isTrue();
    }

    @Test
    void retrievalExceptionSetsGroundingMiss() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenThrow(new RuntimeException("embedder down"));
        RagStep step = new RagStep(retriever, new RetrievalValidator(0.3, 1, 0.3),
                mock(Reranker.class), new RagInjectionScanner(), 24, 10, 0.2);

        PipelineContext ctx = new PipelineContext("s1", "退款政策是什么？");
        ctx.setIntent(com.agentdemo007.intent.Intent.OTHER);
        step.process(ctx);

        assertThat(ctx.groundingMiss()).isTrue();
        assertThat(ctx.degraded()).isTrue(); // 降级语义不变
    }

    @Test
    void successfulInjectionDoesNotSetGroundingMiss() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new RagFragment("退款政策：7日内可申请退款，原路退回。", 0.8, "kb-refund",
                        null, null, null, null, null, true)));
        RagStep step = new RagStep(retriever, new RetrievalValidator(0.3, 1, 0.3),
                mock(Reranker.class), new RagInjectionScanner(), 24, 10, 0.2);

        PipelineContext ctx = new PipelineContext("s1", "退款政策是什么？");
        ctx.setIntent(com.agentdemo007.intent.Intent.OTHER);
        step.process(ctx);

        assertThat(ctx.groundingMiss()).isFalse();
        assertThat(ctx.ragFragments()).isNotEmpty();
    }

    @Test
    void chitChatSkipDoesNotSetGroundingMiss() {
        Retriever retriever = mock(Retriever.class);
        RagStep step = new RagStep(retriever, new RetrievalValidator(0.3, 1, 0.3),
                mock(Reranker.class), new RagInjectionScanner(), 24, 10, 0.2);

        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setIntent(com.agentdemo007.intent.Intent.CHIT_CHAT); // 无 routePlan → 闲聊免 RAG
        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.groundingMiss()).isFalse(); // 正常跳过≠"需要知识却没知识"
    }
}
