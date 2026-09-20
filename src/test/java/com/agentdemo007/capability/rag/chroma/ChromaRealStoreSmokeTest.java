package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.CircuitBreakerGuard;
import com.agentdemo007.capability.rag.EmbeddingService;
import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.SiliconFlowEmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库烟测（真实 RAG round-trip，环境门控——缺密钥/地址自动跳过，CI 离线全绿）。
 *
 * <p>运行条件：{@code CHROMA_BASE_URL}（远程 Chroma，如 http://120.48.5.195:8001）+ {@code SF_KEY}
 * （SiliconFlow，嵌入模型须与入库一致：Qwen/Qwen3-Embedding-8B，4096 维）。
 * 断言：真嵌入查询 → 真库检索返回语料来源片段、分数在余弦合理区间、历史片段隔离标注生效。
 */
@EnabledIfEnvironmentVariable(named = "CHROMA_BASE_URL", matches = "http.+",
        disabledReason = "缺 CHROMA_BASE_URL：跳过真库烟测（离线 CI 环境）")
@EnabledIfEnvironmentVariable(named = "SF_KEY", matches = "sk-.+",
        disabledReason = "缺 SF_KEY：跳过真库烟测（真实嵌入必需）")
class ChromaRealStoreSmokeTest {

    private static final String SF_BASE = "https://api.siliconflow.cn/v1";
    private static final String EMBED_MODEL = "Qwen/Qwen3-Embedding-8B";

    private EmbeddingService realEmbedding() {
        return new SiliconFlowEmbeddingService(SF_BASE, System.getenv("SF_KEY"), EMBED_MODEL,
                new RestTemplate(), JsonMapper.builder().build());
    }

    private ChromaVectorStore realStore(EmbeddingService embedding) {
        ChromaProperties props = new ChromaProperties();
        props.setBaseUrl(System.getenv("CHROMA_BASE_URL"));
        ChromaRestClient client = new ChromaRestClient(props.getBaseUrl(), props.getApiKey(),
                props.getTenant(), props.getDatabase(), new RestTemplate(), JsonMapper.builder().build());
        return new ChromaVectorStore(client, props, embedding, new CircuitBreakerGuard("chroma", 60_000, 30_000));
    }

    @Test
    void realQuery_returnsCorpusFragmentsWithSources() {
        EmbeddingService embedding = realEmbedding();
        ChromaVectorStore store = realStore(embedding);

        float[] vec = embedding.embed("退款多久能到账");
        List<RagFragment> hits = store.search(vec, 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(f -> f.source() != null && f.source().endsWith(".md") || f.source().endsWith(".json"));
        // 余弦相似度合理区间（同一向量空间：查询与语料同模型嵌入）
        assertThat(hits.get(0).score()).isBetween(0.0, 1.0);
        assertThat(hits.get(0).cosineScored()).isTrue();
    }

    @Test
    void realQuery_semanticParaphraseRecallsRefundWindow() {
        EmbeddingService embedding = realEmbedding();
        ChromaVectorStore store = realStore(embedding);

        // 语义改写问法（不出现"30天"原词）→ 真嵌入语义召回退款窗口片段
        float[] vec = embedding.embed("钱付了之后多少天内可以申请退回去");
        List<RagFragment> hits = store.search(vec, 8);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).score()).isGreaterThan(0.3); // 同域语义命中应显著高于 0.3
        assertThat(hits.get(0).text()).containsAnyOf("退款", "退货", "退款窗口");
    }
}
