package com.agentdemo007.capability.kb;

import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import com.agentdemo007.capability.rag.RagFragment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库目录快照单测（[[kb-ingest-design]]·任务3 权限过滤）：
 * 非托管来源恒放行（向后兼容）、PUBLIC 人人可读、PRIVATE 仅名单主体、
 * 下架/换版后快照即时收敛。
 */
class KbCatalogServiceTest {

    private final KbDocumentRepository repository = mock(KbDocumentRepository.class);
    private final KbCatalogService catalog = new KbCatalogService(repository);

    private static KbDocumentEntity doc(KbNamespace ns, String docNo, String principals,
                                        KbDocumentEntity.Status status) {
        return KbDocumentEntity.create(ns, docNo, principals, "标题", "faq.md", "md", null,
                1, "checksum", 3, 100, null, "admin", Instant.now());
    }

    private static RagFragment managed(KbNamespace ns, String docNo, int version, int seq) {
        String docUid = KbSourceRef.docUid(ns, docNo);
        return new RagFragment("正文", 0.5, KbSourceRef.source(docUid, version, seq));
    }

    @Test
    void nonManagedSourcesAlwaysReadable() {
        assertThat(catalog.readable("kb-refund", null)).isTrue();
        assertThat(catalog.readable("corpus/faq/01.md", "10086")).isTrue();
        assertThat(catalog.filterReadable(List.of(
                new RagFragment("a", 1.0, "kb-refund"),
                new RagFragment("b", 1.0, "corpus/x.md")), null)).hasSize(2);
    }

    @Test
    void publicReadableByAnyonePrivateOnlyByPrincipals() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of(
                        doc(KbNamespace.PUBLIC, "faq", null, KbDocumentEntity.Status.ACTIVE),
                        doc(KbNamespace.PRIVATE, "internal", "10086, 10010", KbDocumentEntity.Status.ACTIVE)));
        catalog.loadAll();

        assertThat(catalog.readable(managed(KbNamespace.PUBLIC, "faq", 1, 1).source(), null)).isTrue();
        assertThat(catalog.readable(managed(KbNamespace.PUBLIC, "faq", 1, 1).source(), "99999")).isTrue();
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "internal", 1, 2).source(), "10086")).isTrue();
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "internal", 1, 2).source(), "10010")).isTrue();
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "internal", 1, 2).source(), "99999")).isFalse();
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "internal", 1, 2).source(), null)).isFalse();
    }

    @Test
    void removedOrUnknownManagedSourceUnreadable() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of());
        catalog.loadAll();
        assertThat(catalog.readable(managed(KbNamespace.PUBLIC, "ghost", 1, 1).source(), "u")).isFalse();
    }

    @Test
    void refreshKeepsCatalogInSyncAfterLifecycleChange() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of());
        catalog.loadAll();

        KbDocumentEntity active = doc(KbNamespace.PRIVATE, "vip", "10086", KbDocumentEntity.Status.ACTIVE);
        catalog.refresh(active);
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "vip", 1, 1).source(), "10086")).isTrue();

        active.markDeleted();
        catalog.refresh(active); // DELETED → 移出快照
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "vip", 1, 1).source(), "10086")).isFalse();

        catalog.remove(KbNamespace.PRIVATE, "vip");
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "vip", 1, 1).source(), "10086")).isFalse();
    }

    @Test
    void filterReadablePreservesOrder() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of(doc(KbNamespace.PRIVATE, "vip", "10086", KbDocumentEntity.Status.ACTIVE)));
        catalog.loadAll();

        List<RagFragment> pool = List.of(
                new RagFragment("a", 1.0, "kb-refund"),
                managed(KbNamespace.PRIVATE, "vip", 1, 1),
                managed(KbNamespace.PRIVATE, "secret", 1, 1));
        List<RagFragment> readable = catalog.filterReadable(pool, "10086");
        assertThat(readable).hasSize(2);
        assertThat(readable.get(0).source()).isEqualTo("kb-refund");
    }

    // ---- Phase 21 客户等级可见性（三轴谓词）----

    /** 带可见等级的文档工厂（其余字段对齐既有 doc()）。 */
    private static KbDocumentEntity doc(KbNamespace ns, String docNo, String principals,
                                        KbLevel level, KbDocumentEntity.Status status) {
        return KbDocumentEntity.create(ns, docNo, principals, level, "标题", "faq.md", "md", null,
                1, "checksum", 3, 100, null, "admin", Instant.now());
    }

    @Test
    void levelPredicateGatesPublicDocs() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of(
                        doc(KbNamespace.PUBLIC, "basic", null, KbLevel.V0, KbDocumentEntity.Status.ACTIVE),
                        doc(KbNamespace.PUBLIC, "gold", null, KbLevel.V3, KbDocumentEntity.Status.ACTIVE)));
        catalog.loadAll();

        String basic = managed(KbNamespace.PUBLIC, "basic", 1, 1).source();
        String gold = managed(KbNamespace.PUBLIC, "gold", 1, 1).source();

        // V1 会话查 V3 文档不可见（回归钉死·T97）
        assertThat(catalog.readable(gold, "10010", KbLevel.V1)).isFalse();
        assertThat(catalog.readable(gold, "10086", KbLevel.V5)).isTrue();
        assertThat(catalog.readable(gold, "vip", KbLevel.V3)).isTrue();  // 等级相等可见
        assertThat(catalog.readable(basic, "10010", KbLevel.V1)).isTrue();

        // eval/未登录（V0 fail-closed）：只见 V0 档
        assertThat(catalog.readable(basic, null, null)).isTrue();
        assertThat(catalog.readable(gold, null, null)).isFalse();

        // 既有 2 参兼容口径 = V0 匿名（存量行为零回归）
        assertThat(catalog.readable(basic, "anyone")).isTrue();
        assertThat(catalog.readable(gold, "anyone")).isFalse();
    }

    @Test
    void principalsExceptionOverridesInsufficientLevel() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of(
                        doc(KbNamespace.PUBLIC, "gold", "10010", KbLevel.V3, KbDocumentEntity.Status.ACTIVE),
                        doc(KbNamespace.PRIVATE, "secret", "10010", KbLevel.V3, KbDocumentEntity.Status.ACTIVE)));
        catalog.loadAll();

        // 例外通道优先：名单命中直接放行（等级不足 V1 也可读）
        assertThat(catalog.readable(managed(KbNamespace.PUBLIC, "gold", 1, 1).source(), "10010", KbLevel.V1)).isTrue();
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "secret", 1, 1).source(), "10010", KbLevel.V1)).isTrue();
        // 名单不命中仍按等级/内外边界裁决
        assertThat(catalog.readable(managed(KbNamespace.PRIVATE, "secret", 1, 1).source(), "10086", KbLevel.V5)).isFalse();
    }

    @Test
    void filterReadableCarriesMemberLevel() {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE))
                .thenReturn(List.of(
                        doc(KbNamespace.PUBLIC, "basic", null, KbLevel.V0, KbDocumentEntity.Status.ACTIVE),
                        doc(KbNamespace.PUBLIC, "gold", null, KbLevel.V3, KbDocumentEntity.Status.ACTIVE)));
        catalog.loadAll();

        List<RagFragment> pool = List.of(
                managed(KbNamespace.PUBLIC, "basic", 1, 1),
                managed(KbNamespace.PUBLIC, "gold", 1, 1));
        assertThat(catalog.filterReadable(pool, "10010", KbLevel.V1)).hasSize(1);
        assertThat(catalog.filterReadable(pool, "10086", KbLevel.V5)).hasSize(2);
    }
}
