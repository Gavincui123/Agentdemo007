package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 哈希嵌入服务测试（第四层·dev 向量化内核，确定性 token-bag）。
 *
 * <p>无真实 Embedding 模型即可运行：相同文本向量一致；共享 token 的文本余弦相似度高于无交集文本；
 * 空文本返回零向量。prod 覆盖为 LangChain4j Embedding 桥接（延后）。
 */
class HashEmbeddingServiceTest {

    private final HashEmbeddingService service = new HashEmbeddingService();

    @Test
    void dimension_fixed() {
        assertThat(service.embed("anything").length).isEqualTo(HashEmbeddingService.DIMENSION);
    }

    @Test
    void deterministic_sameText_sameVector() {
        float[] a = service.embed("退款 政策");
        float[] b = service.embed("退款 政策");
        assertThat(a).containsExactly(b);
    }

    @Test
    void similarTexts_higherCosineThanDisjoint() {
        float[] q = service.embed("退款");       // 与 "退款 政策" 共享 token
        float[] similar = service.embed("退款 规则");
        float[] disjoint = service.embed("天气 预报");
        assertThat(cosine(q, similar)).isGreaterThan(cosine(q, disjoint));
        assertThat(cosine(q, similar)).isGreaterThan(0.0);
    }

    @Test
    void emptyText_zeroVector() {
        float[] v = service.embed("");
        assertThat(v).hasSize(HashEmbeddingService.DIMENSION);
        assertThat(norm(v)).isZero();
    }

    @Test
    void nullText_zeroVector() {
        float[] v = service.embed(null);
        assertThat(norm(v)).isZero();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }

    private static double norm(float[] v) {
        double n = 0;
        for (float f : v) n += f * f;
        return Math.sqrt(n);
    }
}
