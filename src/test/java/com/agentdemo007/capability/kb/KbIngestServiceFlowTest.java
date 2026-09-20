package com.agentdemo007.capability.kb;

import com.agentdemo007.capability.kb.KbIngestService.KbIngestCommand;
import com.agentdemo007.capability.kb.KbIngestService.KbIngestResult;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbChunkRepository;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import com.agentdemo007.capability.rag.InMemoryVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库录入全链路集成测（[[kb-ingest-design]]·任务2/3，真实 H2 + InMemoryVectorStore）：
 * 录入→版本换装→下架→命名空间权限过滤，dryRun 零副作用，向量库与 DB、目录快照三方一致。
 */
@SpringBootTest
class KbIngestServiceFlowTest {

    @Autowired
    private KbIngestService service;

    @Autowired
    private KbDocumentRepository documents;

    @Autowired
    private KbChunkRepository chunks;

    @Autowired
    private InMemoryVectorStore vectorStore;

    @Autowired
    private KbCatalogService catalog;

    private final List<String> createdDocNos = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 向量库与 DB 双清（InMemory 非事务资源，@SpringBootTest 共享上下文须手动收敛，防止跨测试泄漏）
        Set<String> sources = vectorStore.fragments().stream()
                .map(com.agentdemo007.capability.rag.RagFragment::source)
                .filter(s -> createdDocNos.stream().anyMatch(no ->
                        s.startsWith("kb:PUBLIC:" + no + ":v") || s.startsWith("kb:PRIVATE:" + no + ":v")))
                .collect(Collectors.toSet());
        if (!sources.isEmpty()) {
            vectorStore.deleteBySources(sources);
        }
        chunks.deleteAllInBatch();
        documents.deleteAll();
        createdDocNos.clear();
    }

    private static byte[] md(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private KbIngestResult ingest(String docNo, String content, KbNamespace ns, String principals, boolean dryRun) {
        KbIngestResult result = service.ingest(new KbIngestCommand(docNo + ".md", md(content),
                docNo, null, ns, principals, "after_sale_policy", "测试版本", "tester", dryRun));
        if (!dryRun) {
            createdDocNos.add(docNo);
        }
        return result;
    }

    @Test
    void ingestIndexesVectorAndPersistsChunks() {
        KbIngestResult r = ingest("faq-v1", "# 退款政策\n退款 3-7 个工作日到账。", KbNamespace.PUBLIC, null, false);

        assertThat(r.version()).isEqualTo(1);
        assertThat(r.indexed()).isTrue();
        assertThat(r.chunkCount()).isGreaterThanOrEqualTo(1);
        assertThat(documents.findById(r.documentId())).isPresent()
                .get().satisfies(d -> {
                    assertThat(d.getStatus()).isEqualTo(KbDocumentEntity.Status.ACTIVE);
                    assertThat(d.getDocType()).isEqualTo("md");
                });
        assertThat(service.chunksOf(r.documentId())).hasSize(r.chunkCount());

        // 向量库与 DB 一致：source 带 docUid:version#seq
        Set<String> sources = vectorStore.fragments().stream()
                .map(com.agentdemo007.capability.rag.RagFragment::source).collect(Collectors.toSet());
        assertThat(sources).contains(KbSourceRef.source(KbSourceRef.docUid(KbNamespace.PUBLIC, "faq-v1"), 1, 1));
    }

    @Test
    void reIngestSameDocNoSupersedesOldVersionAndRemovesOldVectors() {
        ingest("doc-versioned", "# 政策\n旧版内容：签收15日退货。", KbNamespace.PUBLIC, null, false);
        KbIngestResult v2 = ingest("doc-versioned", "# 政策\n新版内容：签收7日退货。", KbNamespace.PUBLIC, null, false);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.supersededVersion()).isEqualTo(1);

        List<KbDocumentEntity> versions = documents.findByNamespaceAndDocNoOrderByVersionDesc(
                KbNamespace.PUBLIC, "doc-versioned");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getStatus()).isEqualTo(KbDocumentEntity.Status.ACTIVE);
        assertThat(versions.get(1).getStatus()).isEqualTo(KbDocumentEntity.Status.SUPERSEDED);

        // 检索只见最新版：v1 片段已从向量库移除
        String docUid = KbSourceRef.docUid(KbNamespace.PUBLIC, "doc-versioned");
        Set<String> sources = vectorStore.fragments().stream()
                .map(com.agentdemo007.capability.rag.RagFragment::source).collect(Collectors.toSet());
        assertThat(sources).contains(KbSourceRef.source(docUid, 2, 1));
        assertThat(sources).doesNotContain(KbSourceRef.source(docUid, 1, 1));
    }

    @Test
    void deleteRemovesVectorsAndBlocksRetrieval() {
        KbIngestResult r = ingest("doc-delete", "# 政策\n待下架内容。", KbNamespace.PUBLIC, null, false);
        String docUid = KbSourceRef.docUid(KbNamespace.PUBLIC, "doc-delete");
        String source = KbSourceRef.source(docUid, 1, 1);
        assertThat(vectorStore.fragments().stream().map(
                com.agentdemo007.capability.rag.RagFragment::source)).contains(source);

        service.delete(r.documentId());

        assertThat(vectorStore.fragments().stream().map(
                com.agentdemo007.capability.rag.RagFragment::source)).doesNotContain(source);
        assertThat(documents.findById(r.documentId()))
                .get().satisfies(d -> assertThat(d.getStatus()).isEqualTo(KbDocumentEntity.Status.DELETED));
        assertThat(catalog.readable(source, "anyone")).isFalse(); // 下架后主体不可见（幂等下架不复活）
    }

    @Test
    void dryRunLeavesNoTrace() {
        long docsBefore = documents.count();
        long chunksBefore = chunks.count();
        int vectorsBefore = vectorStore.fragments().size();

        KbIngestResult r = ingest("doc-dryrun", "# 试运行\n只预览不入库。", KbNamespace.PUBLIC, null, true);

        assertThat(r.indexed()).isFalse();
        assertThat(r.documentId()).isNull();
        assertThat(r.previews()).isNotEmpty();
        assertThat(documents.count()).isEqualTo(docsBefore);
        assertThat(chunks.count()).isEqualTo(chunksBefore);
        assertThat(vectorStore.fragments()).hasSize(vectorsBefore);
    }

    @Test
    void unsupportedFileTypeRejected() {
        assertThatThrownBy(() -> service.ingest(new KbIngestCommand("photo.zip", md("binary-ish"),
                "zip-doc", null, KbNamespace.PUBLIC, null, null, null, "tester", false)))
                .isInstanceOf(KbIngestService.KbIngestException.class)
                .hasMessageContaining("暂不支持");
    }

    @Test
    void emptyExtractionRejected() {
        assertThatThrownBy(() -> service.ingest(new KbIngestCommand("empty.md", md("   \n  "),
                "empty-doc", null, KbNamespace.PUBLIC, null, null, null, "tester", false)))
                .isInstanceOf(KbIngestService.KbIngestException.class)
                .hasMessageContaining("未能从文件中提取到文本");
    }

    @Test
    void privateNamespaceReadableOnlyByPrincipals() {
        ingest("doc-private", "# 内部口径\n仅限名单主体检索。", KbNamespace.PRIVATE, "10086,10010", false);
        String docUid = KbSourceRef.docUid(KbNamespace.PRIVATE, "doc-private");
        String source = KbSourceRef.source(docUid, 1, 1);

        assertThat(catalog.readable(source, "10086")).isTrue();
        assertThat(catalog.readable(source, "other-user")).isFalse();
        assertThat(catalog.readable(source, null)).isFalse(); // 匿名（eval）只见 PUBLIC
    }
}
