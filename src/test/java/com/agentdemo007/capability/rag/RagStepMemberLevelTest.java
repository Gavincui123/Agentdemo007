package com.agentdemo007.capability.rag;

import com.agentdemo007.capability.kb.KbCatalogService;
import com.agentdemo007.capability.kb.KbLevel;
import com.agentdemo007.capability.kb.KbNamespace;
import com.agentdemo007.capability.kb.KbSourceRef;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagStep} × 客户等级可见性（Phase 21 T97 回归钉死）：
 * V1 会话查 V3 文档不可见；<b>引用列表零泄漏</b>——权限过滤发生在粗滤之前，
 * 不可见文档的 source/标题不可能出现在 citation；全滤空走既有 RAG_SKIP 降级不阻塞。
 */
class RagStepMemberLevelTest {

    private final KbDocumentRepository repository = mock(KbDocumentRepository.class);
    private final KbCatalogService catalog = new KbCatalogService(repository);

    private static KbDocumentEntity publicDoc(String docNo, KbLevel level) {
        return KbDocumentEntity.create(KbNamespace.PUBLIC, docNo, null, level, "标题", "faq.md", "md",
                null, 1, "checksum", 1, 10, null, "admin", Instant.now());
    }

    private static RagFragment managed(String docNo, String text) {
        String docUid = KbSourceRef.docUid(KbNamespace.PUBLIC, docNo);
        // 9 参构造（cosineScored=true）：余弦口径过终闸；3 参构造=BM25-only 会被无口径裁决拦下
        return new RagFragment(text, 0.7, KbSourceRef.source(docUid, 1, 1),
                null, null, null, null, null, true);
    }

    private RagStep newStep(Retriever retriever) {
        RagStep step = new RagStep(retriever,
                new RetrievalValidator(0.3, 1, 0.3),
                (query, pool) -> pool, // 池 ≤ top_n 不触重排；占位实现保底
                new RagInjectionScanner(), 24, 3, 0.2);
        step.setKbCatalog(catalog);
        return step;
    }

    @Test
    void v1SessionCannotSeeV3DocAndCitationsLeakNothing() {
        List<KbDocumentEntity> docs = List.of(publicDoc("basic-faq", KbLevel.V0), publicDoc("gold-faq", KbLevel.V3));
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE)).thenReturn(docs);
        docs.forEach(catalog::refresh);
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                managed("basic-faq", "基础退款规则：3-7 个工作日到账。"),
                managed("gold-faq", "黄金会员专属：当日极速退款。")));
        RagStep step = newStep(retriever);

        PipelineContext ctx = new PipelineContext("s-v1", "退款多久到账");
        ctx.setUserId("10010");
        ctx.setMemberLevel(KbLevel.V1);
        StepOutcome out = step.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.ragFragments()).containsExactly("基础退款规则：3-7 个工作日到账。");
        // 引用零泄漏：不可见文档的 source 与正文都不出现在 citation
        assertThat(ctx.ragCitations()).hasSize(1);
        assertThat(ctx.ragCitations().get(0)).doesNotContain("gold-faq").doesNotContain("黄金会员专属");
        assertThat(ctx.groundingMiss()).isFalse();
    }

    @Test
    void allFragmentsFilteredFallsBackToRagSkipWithZeroLeak() {
        List<KbDocumentEntity> docs = List.of(publicDoc("gold-faq", KbLevel.V3));
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE)).thenReturn(docs);
        docs.forEach(catalog::refresh);
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(managed("gold-faq", "黄金会员专属：当日极速退款。")));
        RagStep step = newStep(retriever);

        PipelineContext ctx = new PipelineContext("s-anon", "退款多久到账"); // 匿名/eval：V0 fail-closed
        StepOutcome out = step.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Degrade.class);
        assertThat(ctx.ragFragments()).isEmpty();
        assertThat(ctx.ragCitations()).isEmpty(); // 零泄漏：滤空也不留引用
        assertThat(ctx.groundingMiss()).isTrue();
    }

    @Test
    void v5SessionSeesAllLevels() {
        List<KbDocumentEntity> docs = List.of(publicDoc("basic-faq", KbLevel.V0), publicDoc("gold-faq", KbLevel.V3));
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE)).thenReturn(docs);
        docs.forEach(catalog::refresh);
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                managed("basic-faq", "基础退款规则：3-7 个工作日到账。"),
                managed("gold-faq", "黄金会员专属：当日极速退款。")));
        RagStep step = newStep(retriever);

        PipelineContext ctx = new PipelineContext("s-v5", "退款多久到账");
        ctx.setUserId("10086");
        ctx.setMemberLevel(KbLevel.V5);
        StepOutcome out = step.process(ctx);

        assertThat(out).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.ragFragments()).hasSize(2);
        assertThat(ctx.ragCitations()).hasSize(2);
    }
}
