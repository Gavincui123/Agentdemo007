package com.agentdemo007.capability.kb;

import com.agentdemo007.capability.kb.chunk.ChunkOptions;
import com.agentdemo007.capability.kb.chunk.ChunkingService;
import com.agentdemo007.capability.kb.chunk.ProposedChunk;
import com.agentdemo007.capability.kb.parse.DocumentParserRegistry;
import com.agentdemo007.capability.kb.parse.ParsedDocument;
import com.agentdemo007.capability.kb.parse.TextCleaner;
import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.SourceDeletableStore;
import com.agentdemo007.capability.rag.VectorStore;
import com.agentdemo007.persistence.entity.KbChunkEntity;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbChunkRepository;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库录入服务（[[kb-ingest-design]]·任务2 录入主链 + 任务3 版本/命名空间收口）。
 *
 * <p>录入链序（dryRun 截断在⑥前）：
 * <ol>
 *   <li>扩展名白名单校验（{@link DocumentParserRegistry#supports}）；</li>
 *   <li>SHA-256 指纹（审计/去重）；</li>
 *   <li>解析 → 结构化节段（多格式：md/txt/html/pdf/docx/xlsx/csv/json）；</li>
 *   <li>清洗（{@link TextCleaner}：NFKC/页码行/控制字符/空白规整）；</li>
 *   <li>切块（{@link ChunkingService}：条款感知自动切换 + 通用递归）；</li>
 *   <li>换版收口：同 namespace+docNo 旧 ACTIVE 版 → SUPERSEDED + 按精确 source 清单
 *       广播删除全部 {@link SourceDeletableStore}（稠密+稀疏同步移除，检索只见最新版）；</li>
 *   <li>向量索引（复用 {@link VectorStore} seam：dev InMemory / 真库 Chroma 同路）；</li>
 *   <li>持久化：kb_document + kb_chunk（版本历史永存 DB）+ 目录快照刷新（检索侧权限过滤生效）。</li>
 * </ol>
 * 可提取字符数=0（扫描件 PDF 等）→ 拒绝入库（OCR 属 Python 流水线能力）；
 * 嵌入失败 → 异常上抛整单拒绝（不产生半截知识）。
 */
@Component
@EnableConfigurationProperties(KbProperties.class)
public class KbIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestService.class);

    private final DocumentParserRegistry parsers;
    private final ChunkingService chunker;
    private final VectorStore vectorStore;
    private final List<SourceDeletableStore> deletableStores;
    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbCatalogService catalog;
    private final KbProperties props;

    public KbIngestService(DocumentParserRegistry parsers,
                           ChunkingService chunker,
                           VectorStore vectorStore,
                           List<SourceDeletableStore> deletableStores,
                           KbDocumentRepository documents,
                           KbChunkRepository chunks,
                           KbCatalogService catalog,
                           KbProperties props) {
        this.parsers = parsers;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.deletableStores = deletableStores;
        this.documents = documents;
        this.chunks = chunks;
        this.catalog = catalog;
        this.props = props;
    }

    /** 录入命令（控制器层已做基本非空校验）。 */
    public record KbIngestCommand(String fileName, byte[] content, String docNo, String title,
                                  KbNamespace namespace, String allowedPrincipals, String domain,
                                  String versionNote, String createdBy, boolean dryRun) {
    }

    /** 录入/预览结果（切块预览按 {@code app.kb.preview-limit} 截断）。 */
    public record KbIngestResult(Long documentId, String docNo, String title, KbNamespace namespace,
                                 int version, Integer supersededVersion, String docType, String domain,
                                 int chunkCount, int charCount, String checksum, boolean indexed,
                                 List<ChunkPreview> previews, int totalChunks) {
    }

    /** 切块预览（面包屑 + 正文前 200 字）。 */
    public record ChunkPreview(int seq, String heading, String excerpt, int charCount) {
    }

    /**
     * 录入主链（dryRun=true 时仅解析/清洗/切块预览，不触库不索引）。
     *
     * @throws KbIngestException 文件类型不支持 / 内容不可提取 / 业务校验失败（话术化 message）
     */
    public KbIngestResult ingest(KbIngestCommand cmd) {
        if (cmd.fileName() == null || cmd.fileName().isBlank()) {
            throw new KbIngestException("文件名缺失，无法识别文档类型");
        }
        if (cmd.content() == null || cmd.content().length == 0) {
            throw new KbIngestException("文件内容为空");
        }
        if (!parsers.supports(cmd.fileName())) {
            throw new KbIngestException("暂不支持该文件类型，支持：" + String.join(" / ",
                    parsers.supportedExtensions().stream().sorted().toList()));
        }
        KbNamespace ns = (cmd.namespace() != null) ? cmd.namespace() : KbNamespace.PUBLIC;
        String checksum = sha256(cmd.content());
        Instant now = Instant.now();

        // ③ 解析 → ④ 清洗 → ⑤ 切块
        ParsedDocument parsed;
        List<ProposedChunk> proposed;
        int charCount;
        try {
            parsed = parsers.parse(cmd.fileName(), new ByteArrayInputStream(cmd.content()));
            List<com.agentdemo007.capability.kb.parse.ParsedSection> cleaned = parsed.sections().stream()
                    .map(s -> new com.agentdemo007.capability.kb.parse.ParsedSection(
                            s.headingPath(), TextCleaner.clean(s.text())))
                    .filter(s -> !s.text().isBlank())
                    .toList();
            charCount = cleaned.stream().mapToInt(s -> s.text().length()).sum();
            if (charCount == 0) {
                throw new KbIngestException("未能从文件中提取到文本（扫描件/纯图片 PDF 请走 Python OCR 流水线录入）");
            }
            parsed = new ParsedDocument(resolveTitle(parsed.title(), cmd.title(), cmd.fileName()),
                    parsed.docType(), cleaned);
            proposed = chunker.chunk(parsed, ChunkOptions.of(
                    props.getChunk().getMaxChars(), props.getChunk().getOverlap()));
        } catch (KbIngestException e) {
            throw e;
        } catch (Exception e) {
            throw new KbIngestException("文档解析失败：" + e.getMessage());
        }
        if (proposed.isEmpty()) {
            throw new KbIngestException("清洗切块后无有效内容，请检查文件");
        }

        String docNo = KbSourceRef.sanitizeDocNo(
                (cmd.docNo() != null && !cmd.docNo().isBlank()) ? cmd.docNo()
                        : com.agentdemo007.capability.kb.parse.MarkdownTextParser.fileNameBase(cmd.fileName()));
        String docUid = KbSourceRef.docUid(ns, docNo);
        Integer supersededVersion = null;

        if (cmd.dryRun()) {
            return result(null, docNo, parsed.title(), ns, 0, null, parsed.docType(), cmd.domain(),
                    checksum, charCount, false, proposed);
        }

        // ⑥ 换版收口：旧 ACTIVE 版 → SUPERSEDED + 精确 source 删向量（稠密+稀疏同步）
        KbDocumentEntity latest = documents.findTopByNamespaceAndDocNoOrderByVersionDesc(ns, docNo).orElse(null);
        int version = (latest != null) ? latest.getVersion() + 1 : 1;
        if (latest != null && latest.getStatus() == KbDocumentEntity.Status.ACTIVE) {
            supersededVersion = latest.getVersion();
            List<String> oldSources = chunks.findByDocUidAndVersion(docUid, latest.getVersion()).stream()
                    .map(c -> KbSourceRef.source(docUid, latest.getVersion(), c.getSeq()))
                    .collect(Collectors.toList());
            deleteFromStores(oldSources);
            for (KbDocumentEntity old : documents.findByNamespaceAndDocNoAndStatus(
                    ns, docNo, KbDocumentEntity.Status.ACTIVE)) {
                old.markSuperseded(now);
                documents.save(old);
            }
        }

        // ⑦ 向量索引（chunk 预算内嵌入；失败上抛 → 不落库，不留半截知识）
        List<RagFragment> fragments = new ArrayList<>(proposed.size());
        for (ProposedChunk c : proposed) {
            fragments.add(new RagFragment(c.indexedText(), 0.0,
                    KbSourceRef.source(docUid, version, c.seq()),
                    now, null, "CURRENT", cmd.domain(), null, false));
        }
        vectorStore.index(fragments);

        // ⑧ 持久化 + 目录快照刷新
        KbDocumentEntity doc = KbDocumentEntity.create(ns, docNo, cmd.allowedPrincipals(),
                parsed.title(), cmd.fileName(), parsed.docType(), cmd.domain(), version,
                checksum, proposed.size(), charCount, cmd.versionNote(), cmd.createdBy(), now);
        doc = documents.save(doc);
        List<KbChunkEntity> chunkRows = new ArrayList<>(proposed.size());
        for (ProposedChunk c : proposed) {
            chunkRows.add(KbChunkEntity.create(doc.getId(), docUid, version, c.seq(),
                    c.heading(), c.text(), now));
        }
        chunks.saveAll(chunkRows);
        catalog.refresh(doc);
        log.info("知识库录入完成：docUid={} v{} chunks={} chars={} superseded={} dryRun=false",
                docUid, version, proposed.size(), charCount, supersededVersion);
        return result(doc.getId(), docNo, doc.getTitle(), ns, version, supersededVersion,
                doc.getDocType(), doc.getDomain(), checksum, charCount, true, proposed);
    }

    /**
     * 下架文档：按精确 source 清单广播删向量 + 状态 DELETED + 目录快照移除。
     *
     * @return 被删除向量条数口径（-1=实现不返回计数）
     */
    public int delete(Long documentId) {
        KbDocumentEntity doc = documents.findById(documentId)
                .orElseThrow(() -> new KbIngestException("文档不存在：" + documentId));
        if (doc.getStatus() == KbDocumentEntity.Status.DELETED) {
            return 0; // 幂等
        }
        String docUid = KbSourceRef.docUid(doc.getNamespace(), doc.getDocNo());
        List<String> sources = chunks.findByDocUidAndVersion(docUid, doc.getVersion()).stream()
                .map(c -> KbSourceRef.source(docUid, doc.getVersion(), c.getSeq()))
                .collect(Collectors.toList());
        int removed = deleteFromStores(sources);
        doc.markDeleted();
        documents.save(doc);
        catalog.remove(doc.getNamespace(), doc.getDocNo());
        log.info("知识库下架完成：docUid={} v{} status={} 删除向量口径={}",
                docUid, doc.getVersion(), doc.getStatus(), removed);
        return removed;
    }

    /** 切块清单（详情页）。 */
    public List<KbChunkEntity> chunksOf(Long documentId) {
        return chunks.findByDocumentIdOrderBySeq(documentId);
    }

    /** 广播删除全部实现（稠密/稀疏/内存），单通道失败告警不阻塞（Lucene 下次同步对齐）。 */
    private int deleteFromStores(List<String> sources) {
        if (sources.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (SourceDeletableStore store : deletableStores) {
            try {
                removed = Math.max(removed, store.deleteBySources(sources));
            } catch (Exception e) {
                log.warn("向量删除通道失败（尽力而为，不影响换版主链）：store={} reason={}",
                        store.getClass().getSimpleName(), e.getMessage());
            }
        }
        return removed;
    }

    private static String resolveTitle(String parsedTitle, String cmdTitle, String fileName) {
        if (cmdTitle != null && !cmdTitle.isBlank()) {
            return cmdTitle.strip();
        }
        if (parsedTitle != null && !parsedTitle.isBlank()) {
            return parsedTitle;
        }
        return com.agentdemo007.capability.kb.parse.MarkdownTextParser.fileNameBase(fileName);
    }

    private KbIngestResult result(Long documentId, String docNo, String title, KbNamespace ns,
                                  int version, Integer supersededVersion, String docType, String domain,
                                  String checksum, int charCount, boolean indexed, List<ProposedChunk> proposed) {
        int limit = props.getPreviewLimit();
        List<ChunkPreview> previews = proposed.stream()
                .limit(limit)
                .map(c -> new ChunkPreview(c.seq(), c.heading(),
                        excerpt(c.text()), c.text().length()))
                .toList();
        return new KbIngestResult(documentId, docNo, title, ns, version, supersededVersion,
                docType, domain, proposed.size(), charCount, checksum, indexed, previews, proposed.size());
    }

    private static String excerpt(String text) {
        String t = text.strip();
        return (t.length() <= 200) ? t : t.substring(0, 200) + "…";
    }

    static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 录入业务异常（话术化 message；控制器转 {@code UnifiedResponse.error(BAD_REQUEST, message)}）。 */
    public static class KbIngestException extends RuntimeException {
        public KbIngestException(String message) {
            super(message);
        }
    }
}
