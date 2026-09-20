package com.agentdemo007.capability.rag.lucene;

import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.chroma.ChromaProperties;
import com.agentdemo007.capability.rag.chroma.ChromaRestClient;
import com.agentdemo007.capability.rag.chroma.ChromaVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LuceneBm25IndexService} 单测（磁盘倒排 BM25 稀疏通道，@TempDir 全离线）。
 *
 * <p>断言：① Chroma 分页流式同步 → 磁索可检（中文查询命中、BM25 分降序、metadata 契约映射、
 * {@code cosineScored=false}）；② 同步幂等（重复同步不重复计数）+ 陈旧清理（Chroma 删文后索引对齐）；
 * ③ Chroma 不可达 → 同步返 null 且沿用现有索引继续检索（②每步降级）；④ 空查询守卫。
 */
class LuceneBm25IndexServiceTest {

    private static final String COLLECTION_ID = "c-1";

    private static ChromaRestClient.ChromaDoc doc(String text, String source, String domain) {
        return new ChromaRestClient.ChromaDoc("chroma-internal-" + text.hashCode(), text,
                Map.of("source", source, "domain", domain));
    }

    private static void stubDocs(ChromaRestClient client, List<ChromaRestClient.ChromaDoc> docs) {
        when(client.resolveCollection("kb_customer_service")).thenReturn(
                new ChromaRestClient.CollectionRef(COLLECTION_ID, "kb_customer_service", 4096));
        doAnswer(inv -> {
            var consumer = (java.util.function.Consumer<ChromaRestClient.ChromaDoc>) inv.getArgument(1);
            docs.forEach(consumer);
            return null;
        }).when(client).forEachDocument(anyString(), any());
    }

    @Test
    void syncThenSearch_chineseQueryHits_withMetadataContract(@TempDir Path dir) throws Exception {
        ChromaRestClient client = mock(ChromaRestClient.class);
        stubDocs(client, List.of(
                doc("退款政策：自订单支付成功之日起 30 天内可发起退款申请，超过窗口系统将拒绝受理。",
                        "01-refund-policy.md", "after_sale_policy"),
                doc("双11预售活动：10月20日20点开启定金支付，尾款10月31日支付。",
                        "22-activity-rules-2026q4.json", "promotion_and_member_policy")));
        try (LuceneBm25IndexService service = new LuceneBm25IndexService(
                client, new ChromaProperties(), dir)) {
            LuceneBm25IndexService.SyncStats stats = service.syncFromChroma();

            assertThat(stats).isNotNull();
            assertThat(stats.upserts()).isEqualTo(2);

            List<RagFragment> hits = service.search("退款 多少天 可以 申请", 5);

            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).text()).contains("退款政策");
            assertThat(hits.get(0).source()).isEqualTo("01-refund-policy.md");
            assertThat(hits.get(0).domain()).isEqualTo("after_sale_policy");
            assertThat(hits.get(0).cosineScored()).isFalse();  // BM25 分仅排序，未经理裁决不得过终闸
            assertThat(hits.get(0).relevance()).isNull();
            // BM25 SHOULD 查询：弱相关文档可低分入池，但退款文档必须以显著分差居首
            assertThat(hits).allSatisfy(f -> assertThat(f.score()).isGreaterThan(0.0));
            assertThat(hits.get(0).score()).isGreaterThan(hits.get(hits.size() - 1).score());
        }
    }

    @Test
    void syncIdempotent_andPurgesStaleDocs(@TempDir Path dir) throws Exception {
        ChromaRestClient client = mock(ChromaRestClient.class);
        try (LuceneBm25IndexService service = new LuceneBm25IndexService(
                client, new ChromaProperties(), dir)) {
            stubDocs(client, List.of(
                    doc("退款流程说明甲", "a.md", "after_sale_policy"),
                    doc("退款流程说明乙", "b.md", "after_sale_policy")));
            assertThat(service.syncFromChroma().upserts()).isEqualTo(2);
            assertThat(service.syncFromChroma().upserts()).isEqualTo(2); // 幂等：重同步仍 upsert 2（同 id 覆盖）

            // Chroma 删除了乙 → 重同步后陈旧清理 1 条，甲仍可检
            stubDocs(client, List.of(doc("退款流程说明甲", "a.md", "after_sale_policy")));
            LuceneBm25IndexService.SyncStats stats = service.syncFromChroma();
            assertThat(stats.purged()).isEqualTo(1);

            List<RagFragment> hits = service.search("退款", 10);
            assertThat(hits).anyMatch(f -> f.text().contains("甲"));
            assertThat(hits).noneMatch(f -> f.text().contains("乙"));
        }
    }

    @Test
    void chromaUnreachable_syncReturnsNull_existingIndexStillSearchable(@TempDir Path dir) throws Exception {
        ChromaRestClient client = mock(ChromaRestClient.class);
        try (LuceneBm25IndexService service = new LuceneBm25IndexService(
                client, new ChromaProperties(), dir)) {
            stubDocs(client, List.of(doc("退款到账规则", "a.md", "after_sale_policy")));
            service.syncFromChroma();

            // 之后 Chroma 不可达（resolveCollection 抛）
            when(client.resolveCollection(anyString()))
                    .thenThrow(new IllegalStateException("Chroma 连接失败"));
            assertThat(service.syncFromChroma()).isNull(); // 告警 + 沿用现有索引

            assertThat(service.search("退款", 5)).isNotEmpty(); // 稀疏通道不中断
        }
    }

    @Test
    void collectionMissing_syncReturnsNull(@TempDir Path dir) throws Exception {
        ChromaRestClient client = mock(ChromaRestClient.class);
        when(client.resolveCollection("kb_customer_service")).thenReturn(null);
        try (LuceneBm25IndexService service = new LuceneBm25IndexService(
                client, new ChromaProperties(), dir)) {
            assertThat(service.syncFromChroma()).isNull();
            assertThat(service.search("退款", 5)).isEmpty(); // 空索引
        }
    }

    @Test
    void blankOrTokenlessQuery_returnsEmpty(@TempDir Path dir) throws Exception {
        ChromaRestClient client = mock(ChromaRestClient.class);
        try (LuceneBm25IndexService service = new LuceneBm25IndexService(
                client, new ChromaProperties(), dir)) {
            assertThat(service.search("", 5)).isEmpty();
            assertThat(service.search(null, 5)).isEmpty();
            assertThat(service.search("   ", 5)).isEmpty();
        }
    }

    @Test
    void chunkId_consistentWithChromaVectorStoreContract(@TempDir Path dir) throws Exception {
        // Lucene 同步与稠密库共用确定性 chunk id（sha256(text#source)[:32]）——双索引 ID 天然统一
        assertThat(ChromaVectorStore.chunkId("文本", "src"))
                .isEqualTo(ChromaVectorStore.chunkId("文本", "src"))
                .hasSize(32);
    }
}
