package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.Retriever;
import com.agentdemo007.capability.rag.VectorRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChromaHybridRetriever} 单测（真库宽召回融合）。
 *
 * <p>断言：① 稀疏(BM25)命中优先置顶（精确词提权）、稠密补齐；② 同文本双命中保留<b>稠密副本</b>
 * （余弦口径可过置信度终闸）；③ 融合不截断（候选池完整交上层漏斗）；④ 稠密通道异常降级稀疏-only；
 * ⑤ 稀疏召回预算独立（recall-sparse）。
 */
class ChromaHybridRetrieverTest {

    /** 稠密桩：固定返回余弦口径片段。 */
    private static Retriever dense(RagFragment... fragments) {
        return (query, topK) -> List.of(fragments);
    }

    private ChromaHybridRetriever newRetriever(Retriever denseChannel, Retriever sparseChannel) {
        // 稠密桩：VectorRetriever 匿名子类覆写 retrieve（不触 store，构造参数仅占位）
        VectorRetriever dense = new VectorRetriever(text -> new float[0], null) {
            @Override
            public List<RagFragment> retrieve(String query, int topK) {
                return denseChannel.retrieve(query, topK);
            }
        };
        return new ChromaHybridRetriever(dense, sparseChannel, 24);
    }

    @Test
    void sparseHitsFirst_denseFills_noTruncation() {
        // 稀疏桩（LuceneBm25Retriever 同形 Retriever）：BM25 词面命中置顶提权
        Retriever sparseStub = (q, k) -> {
            List<RagFragment> ranked = List.of(
                    new RagFragment("退货政策七日无理由", 0.0, "kb-return"),
                    new RagFragment("退款到账三至七个工作日", 0.0, "kb-refund"),
                    new RagFragment("会员满减活动规则", 0.0, "kb-member"));
            return k >= 0 && ranked.size() > k ? ranked.subList(0, k) : ranked;
        };
        Retriever denseStub = dense(new RagFragment("会员满减活动规则", 0.8, "kb-member", null, null, null, null, null, true));
        ChromaHybridRetriever retriever = newRetriever(denseStub, sparseStub);

        // retrieve topK=1（稠密预算）→ 融合不截断：池含稀疏命中 + 稠密命中
        List<RagFragment> pool = retriever.retrieve("退货 政策", 1);

        assertThat(pool).isNotEmpty();
        assertThat(pool.get(0).text()).contains("退货政策"); // BM25 词面命中置顶提权
        // 稠密命中在池中（不截断）
        assertThat(pool).anyMatch(f -> f.text().contains("会员满减"));
    }

    @Test
    void duplicateText_keepsDenseCopyWithCosine() {
        Retriever sparseStub = (q, k) -> List.of(new RagFragment("退款到账规则", 0.0, "kb-refund"));
        // 稠密通道返回同文本（余弦 0.75）→ 融合去重保留稠密副本
        Retriever denseStub = dense(new RagFragment("退款到账规则", 0.75, "kb-refund", null, null, null, null, null, true));
        ChromaHybridRetriever retriever = newRetriever(denseStub, sparseStub);

        List<RagFragment> pool = retriever.retrieve("退款", 5);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).score()).isEqualTo(0.75); // 稠密副本（余弦口径），非 BM25 词面分 0.0
        assertThat(pool.get(0).cosineScored()).isTrue();
    }

    @Test
    void denseChannelFailure_degradesToSparseOnly() {
        Retriever sparseStub = (q, k) -> List.of(new RagFragment("退货政策七日无理由", 0.0, "kb-return"));
        Retriever throwingDense = (q, k) -> { throw new RuntimeException("embedding down"); };
        ChromaHybridRetriever retriever = newRetriever(throwingDense, sparseStub);

        List<RagFragment> pool = retriever.retrieve("退货", 5);

        assertThat(pool).isNotEmpty(); // 稀疏-only 继续（②每步降级）
        assertThat(pool.get(0).cosineScored()).isFalse(); // BM25-only：未经理裁决不得过终闸
    }

    @Test
    void sparseOnlyCopy_notCosineScored() {
        // 稀疏命中不在稠密结果中 → BM25-only 副本（cosineScored=false），重排裁决或被终闸丢弃
        Retriever sparseStub = (q, k) -> List.of(new RagFragment("退货政策七日无理由", 0.0, "kb-return"));
        Retriever emptyDense = (q, k) -> List.of();
        ChromaHybridRetriever retriever = newRetriever(emptyDense, sparseStub);

        List<RagFragment> pool = retriever.retrieve("退货", 5);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).cosineScored()).isFalse();
        assertThat(pool.get(0).score()).isEqualTo(0.0); // 桩未打分；真实 Lucene 通道为 BM25 词面分（仅排序）
    }
}
