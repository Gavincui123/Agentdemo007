package com.agentdemo007.capability.rag.lucene;

import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.chroma.ChromaProperties;
import com.agentdemo007.capability.rag.chroma.ChromaRestClient;
import com.agentdemo007.capability.rag.chroma.ChromaVectorStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lucene 磁盘倒排 BM25 索引服务（真实 RAG·稀疏通道生产实现，替代 JVM 内存语料全量）。
 *
 * <p><b>为什么是 Lucene</b>：用户生产顾虑——单机服务器内存被中间件占用，JVM 常驻全量语料过不了
 * 生产级；Chroma 原生稀疏索引/Search API 经实测仅分布式/Chroma Cloud 支持（单机 1.0.8/1.5.9 双
 * 验证：「not enabled in local」/「not implemented for local executor」，2026-09-17，教程 FAQ 有记），
 * 且官方 BM25 分词器为英文口径（空白切分 + 40 字上限，600 字中文块切出 0 token）。
 * Lucene {@link MMapDirectory} 索引落盘、堆内存极小（读走 OS 页缓存），BM25 为内置相似度
 * （k1=1.2/b=0.75，与项目 {@code Bm25Reranker} 同参数）；{@link StandardAnalyzer} 对 CJK 逐字切分
 * （UAX29 Ideographic），与 dev {@code HashEmbeddingService.tokenize} 口径一致。
 *
 * <p><b>同步</b>：启动时从 Chroma <b>分页流式</b>拉取（{@code forEachDocument}，常驻内存 O(1) 页，
 * 不累积全量）→ 确定性 chunk id（sha256(text#source)[:32]，与入库流水线/稠密库一致——双索引 ID 天然
 * 统一）→ {@code updateDocument} 幂等 upsert → 清理 Chroma 已删的陈旧文档 → commit。
 * 同步失败（Chroma 不可达/集合缺失）→ 告警并保留现有磁盘索引继续服务（不阻塞启动，②每步降级）；
 * Python 重新入库后重启即增量对齐。
 *
 * <p><b>分数口径</b>：Lucene BM25 分仅用于排序（{@code cosineScored=false}）——置信度终闸下
 * BM25-only 候选未经理裁决不得入上下文，与既有「嵌入确认制」纪律一致。
 */
public class LuceneBm25IndexService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneBm25IndexService.class);

    static final String FIELD_ID = "id";
    static final String FIELD_TEXT = "text";
    static final String FIELD_SOURCE = "source";
    static final String FIELD_DOMAIN = "domain";
    static final String FIELD_TEMPORAL_TAG = "temporal_tag";
    static final String FIELD_VALID_UNTIL = "valid_until";
    static final String FIELD_UPDATED = "updated";

    private final ChromaRestClient chromaClient;
    private final ChromaProperties chromaProps;
    private final Path indexDir;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final Object syncLock = new Object();

    public LuceneBm25IndexService(ChromaRestClient chromaClient, ChromaProperties chromaProps,
                                  Path indexDir) throws IOException {
        this.chromaClient = chromaClient;
        this.chromaProps = chromaProps;
        this.indexDir = indexDir;
        this.analyzer = new StandardAnalyzer();
        MMapDirectory dir = new MMapDirectory(indexDir);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setSimilarity(new BM25Similarity()); // k1=1.2 / b=0.75（与 Bm25Reranker 同参数）
        this.writer = new IndexWriter(dir, config);
        this.searcherManager = new SearcherManager(writer, null);
    }

    /**
     * 从 Chroma 分页流式同步全量语料到磁盘索引（幂等，可重复调用）。
     *
     * @return 同步统计（用于日志/测试断言）；Chroma 不可达/集合缺失返回 null（保留现有索引）
     */
    public SyncStats syncFromChroma() {
        synchronized (syncLock) {
            com.agentdemo007.capability.rag.chroma.ChromaRestClient.CollectionRef ref;
            try {
                ref = chromaClient.resolveCollection(chromaProps.getCollection());
            } catch (Exception e) {
                log.warn("Lucene BM25 同步中止（Chroma 不可达），沿用现有磁盘索引继续服务：reason={}", e.getMessage());
                return null;
            }
            if (ref == null) {
                log.warn("Lucene BM25 同步中止：Chroma 集合 [{}] 不存在（先运行入库流水线），"
                        + "沿用现有磁盘索引继续服务", chromaProps.getCollection());
                return null;
            }
            try {
                Set<String> seenIds = new HashSet<>();
                long[] upserts = {0};
                chromaClient.forEachDocument(ref.id(), doc -> {
                    try {
                        String chunkId = ChromaVectorStore.chunkId(doc.document(),
                                metaString(doc.metadata(), "source"));
                        if (seenIds.add(chunkId)) { // 同 id 碰撞只保留首条（与稠密库确定性 id 同口径）
                            writer.updateDocument(new Term(FIELD_ID, chunkId), toDocument(chunkId, doc));
                            upserts[0]++;
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Lucene 索引写入失败：" + e.getMessage(), e);
                    }
                });
                long purged = purgeStale(seenIds);
                writer.commit();
                searcherManager.maybeRefreshBlocking();
                SyncStats stats = new SyncStats(ref.id(), upserts[0], purged);
                log.info("Lucene BM25 磁盘索引同步完成：collection={} upserts={} purged={} dir={}",
                        ref.id(), upserts[0], purged, indexDir);
                return stats;
            } catch (Exception e) {
                log.warn("Lucene BM25 同步失败，沿用现有磁盘索引继续服务（下次启动重试增量对齐）：reason={}", e.getMessage());
                return null;
            }
        }
    }

    /** 清理 Chroma 已删除的陈旧文档（索引现存 id 全量扫描——每次同步一次，IO 有界）。 */
    private long purgeStale(Set<String> seenIds) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            List<Term> stale = new ArrayList<>();
            TopDocs all = searcher.search(new org.apache.lucene.search.MatchAllDocsQuery(),
                    Integer.MAX_VALUE);
            for (ScoreDoc sd : all.scoreDocs) {
                String id = searcher.storedFields().document(sd.doc).get(FIELD_ID);
                if (id != null && !seenIds.contains(id)) {
                    stale.add(new Term(FIELD_ID, id));
                }
            }
            if (!stale.isEmpty()) {
                writer.deleteDocuments(stale.toArray(new Term[0]));
            }
            return stale.size();
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * BM25 关键词检索（磁盘倒排，Top-K 按相关度降序）。
     *
     * @return 候选片段（{@code cosineScored=false}——BM25 分仅排序，未经理裁决不得过置信度终闸）；
     *         空查询/无有效 token/索引空返回空
     */
    public List<RagFragment> search(String query, int topK) {
        if (query == null || query.isBlank() || topK == 0) {
            return List.of();
        }
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                BooleanQuery.Builder qb = new BooleanQuery.Builder();
                for (String token : tokens) {
                    qb.add(new TermQuery(new Term(FIELD_TEXT, token)), BooleanClause.Occur.SHOULD);
                }
                TopDocs top = searcher.search(qb.build(), topK);
                List<RagFragment> out = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc sd : top.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    out.add(new RagFragment(doc.get(FIELD_TEXT), sd.score,
                            blankToNull(doc.get(FIELD_SOURCE)),
                            ChromaVectorStore.parseInstant(doc.get(FIELD_UPDATED)),
                            ChromaVectorStore.parseInstant(doc.get(FIELD_VALID_UNTIL)),
                            blankToNull(doc.get(FIELD_TEMPORAL_TAG)),
                            blankToNull(doc.get(FIELD_DOMAIN)),
                            null, false));
                }
                return out;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Lucene BM25 检索失败（磁盘索引 IO）：dir=" + indexDir
                    + " reason=" + e.getMessage(), e);
        }
    }

    /**
     * 按 source 精确删除稀疏索引记录（[[kb-ingest-design]] 知识库换版/下架配套）：
     * FIELD_SOURCE 是 StringField（精确 Term 匹配，不分词），与 Chroma where $in 同口径。
     * 失败仅告警不抛（best-effort：下次启动 syncFromChroma 以 Chroma 为准增量对齐）。
     *
     * @return 删除条数（按 Term 计；无匹配为 0）
     */
    public int deleteBySources(java.util.Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        synchronized (syncLock) {
            try {
                List<Term> terms = sources.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> new Term(FIELD_SOURCE, s))
                        .toList();
                if (terms.isEmpty()) {
                    return 0;
                }
                writer.deleteDocuments(terms.toArray(new Term[0]));
                writer.commit();
                searcherManager.maybeRefreshBlocking();
                log.info("Lucene BM25 按来源删除完成：terms={} dir={}", terms.size(), indexDir);
                return terms.size();
            } catch (Exception e) {
                log.warn("Lucene BM25 按来源删除失败（下次启动同步增量对齐）：reason={}", e.getMessage());
                return -1;
            }
        }
    }

    /** 与 dev 口径一致的查询分词（复用索引 analyzer；analyze 产出即 StandardAnalyzer 的 CJK 逐字 + ASCII 词）。 */
    List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        try (var stream = analyzer.tokenStream(FIELD_TEXT, query)) {
            var charTerm = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(charTerm.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new IllegalStateException("查询分词失败：" + e.getMessage(), e);
        }
        return tokens;
    }

    private static Document toDocument(String chunkId, ChromaRestClient.ChromaDoc doc) {
        Map<String, Object> meta = doc.metadata();
        Document d = new Document();
        d.add(new StringField(FIELD_ID, chunkId, Field.Store.YES));
        d.add(new TextField(FIELD_TEXT, doc.document(), Field.Store.YES));
        d.add(new StringField(FIELD_SOURCE, nullToEmpty(metaString(meta, "source")), Field.Store.YES));
        d.add(new StringField(FIELD_DOMAIN, nullToEmpty(metaString(meta, "domain")), Field.Store.YES));
        d.add(new StringField(FIELD_TEMPORAL_TAG, nullToEmpty(metaString(meta, "temporal_tag")), Field.Store.YES));
        d.add(new org.apache.lucene.document.StoredField(FIELD_VALID_UNTIL, nullToEmpty(metaString(meta, "valid_until"))));
        d.add(new org.apache.lucene.document.StoredField(FIELD_UPDATED, nullToEmpty(metaString(meta, "updated"))));
        return d;
    }

    private static String metaString(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return (v != null && !String.valueOf(v).isBlank()) ? String.valueOf(v) : null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /** 同步统计（新增/更新条数 + 陈旧清理条数）。 */
    public record SyncStats(String collectionId, long upserts, long purged) { }
}
