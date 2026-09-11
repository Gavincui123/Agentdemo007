package com.agentdemo007.capability.rag;

import com.agentdemo007.common.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagStep} 时效标注抽取测评（Phase 20·T90c）。
 *
 * <p>RagStep 在 RAG→文本抽取边界经 {@link RagFragment#displayText()} 框定片段：
 * 历史片段（{@code temporalTag=HISTORICAL}）→ 隔离标注"【历史参考资料·截至{date}】"前缀入
 * {@code ragFragments}（过时不冒充当前，§5.4.1）；当前片段 → 原文。§5.14 收口：仅文本 + 时效
 * 标注外泄到步骤间，不外泄 bespoke 结构（score/temporalTag 不入 {@code ragFragments} 列表）。
 *
 * <p>既有种子语料无 temporal（temporalTag=null → 原文），既有 {@code RagStepTest} 断言
 * {@code contains("退款流程说明")} 等不受影响。
 */
class RagStepTemporalFramingTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();

    private RagStep newStep(InMemoryVectorStore store, double minScore, int minCount, int topK) {
        return new RagStep(new VectorRetriever(embedding, store),
                new RetrievalValidator(minScore, minCount),
                new Bm25Reranker(), new RagInjectionScanner(), topK);
    }

    @Test
    void historicalFragment_framedWithLabelInRagFragments() {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        Instant vu = Instant.parse("2024-06-30T00:00:00Z");
        store.index(List.of(new RagFragment("旧退款政策", 0.0, "doc1", null, vu, "HISTORICAL")));
        RagStep step = newStep(store, 0.1, 1, 5);

        PipelineContext ctx = new PipelineContext("s1", "旧退款政策");
        step.process(ctx);

        assertThat(ctx.ragFragments()).anyMatch(t -> t.contains("【历史参考资料·截至2024-06-30】"));
        assertThat(ctx.ragFragments()).anyMatch(t -> t.contains("旧退款政策"));
    }

    @Test
    void currentFragment_noLabel_plainText() {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        store.index(List.of(new RagFragment("退款流程", 0.0, "doc1",
                Instant.parse("2024-06-30T00:00:00Z"), null, "CURRENT")));
        RagStep step = newStep(store, 0.1, 1, 5);

        PipelineContext ctx = new PipelineContext("s1", "退款流程");
        step.process(ctx);

        assertThat(ctx.ragFragments()).contains("退款流程"); // 当前 → 原文（exact）
        assertThat(ctx.ragFragments()).noneMatch(t -> t.contains("历史参考资料"));
    }
}
