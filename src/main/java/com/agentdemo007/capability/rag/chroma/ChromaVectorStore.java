package com.agentdemo007.capability.rag.chroma;

import com.agentdemo007.capability.rag.CircuitBreakerGuard;
import com.agentdemo007.capability.rag.EmbeddingService;
import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chroma 向量库（第四层·{@link VectorStore} seam 真库实现，真实 RAG）。
 *
 * <p><b>search</b>：cosine 距离 → 相似度 {@code score = 1 - distance}（集合由入库流水线固定
 * {@code hnsw:space=cosine}，实测确认）；metadata 契约映射
 * {@code source→source / valid_until→validUntil / temporal_tag→temporalTag / domain→domain /
 * updated→timestamp}，全部片段 {@code cosineScored=true}（余弦口径可过置信度终闸）。
 * 维度守卫：查询向量维度 ≠ 集合维度（4096）即抛带行动指引的异常——提示需
 * {@code EMBEDDING_ENABLED=true} 且模型与入库一致（Qwen/Qwen3-Embedding-8B），
 * 由 {@code HybridRetriever} 同构降级链承接（稠密失败→BM25-only）。
 *
 * <p><b>index</b>：嵌入后确定性 id（{@code sha256(text#source)[:32]}，与入库流水线同口径）幂等
 * upsert——语料真相源仍是 Python 入库流水线，本方法仅供运行期知识注入（真库模式种子已禁用）。
 *
 * <p><b>集合解析</b>：懒解析 + 每调用重试——启动时集合缺失仅告警不阻塞（②每步降级），
 * 入库完成后首次检索自动恢复。熔断守卫包 query/upsert：Chroma 故障期快速失败降级，
 * 不吃满读超时（与 embedding/rerank 同构）。
 */
public class ChromaVectorStore implements VectorStore, com.agentdemo007.capability.rag.SourceDeletableStore {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorStore.class);

    private final ChromaRestClient client;
    private final ChromaProperties props;
    private final EmbeddingService embedding;
    private final CircuitBreakerGuard guard;
    private volatile ChromaRestClient.CollectionRef ref;

    public ChromaVectorStore(ChromaRestClient client, ChromaProperties props,
                             EmbeddingService embedding, CircuitBreakerGuard guard) {
        this.client = client;
        this.props = props;
        this.embedding = embedding;
        this.guard = guard;
    }

    @Override
    public List<RagFragment> search(float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK == 0) {
            return List.of(); // 无效查询/零召回（seam 契约：零范数/空向量 → 空）
        }
        ChromaRestClient.CollectionRef collectionRef = ensureRef();
        if (collectionRef == null) {
            throw new IllegalStateException("Chroma 集合 [" + props.getCollection() + "] 不存在："
                    + "先运行入库流水线（scripts/rag-ingest → python3 ingest.py ...）");
        }
        if (queryVector.length != collectionRef.dimension()) {
            throw new IllegalStateException("向量维度不一致：查询 " + queryVector.length
                    + " vs 集合 " + collectionRef.dimension() + "——需 EMBEDDING_ENABLED=true "
                    + "且模型与入库一致（Qwen/Qwen3-Embedding-8B，4096 维；换模型=全量重灌）");
        }
        List<ChromaRestClient.ChromaHit> hits =
                guard.call(() -> client.query(collectionRef.id(), queryVector, topK));
        List<RagFragment> fragments = new ArrayList<>(hits.size());
        for (ChromaRestClient.ChromaHit hit : hits) {
            Map<String, Object> meta = hit.metadata();
            double score = 1.0 - hit.distance(); // cosine 距离 → 相似度
            fragments.add(new RagFragment(hit.document(), score, metaString(meta, "source"),
                    parseInstant(metaString(meta, "updated")), parseInstant(metaString(meta, "valid_until")),
                    metaString(meta, "temporal_tag"), metaString(meta, "domain"),
                    null, true));
        }
        return fragments;
    }

    @Override
    public void index(List<RagFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return;
        }
        ChromaRestClient.CollectionRef collectionRef = ensureRef();
        if (collectionRef == null) {
            throw new IllegalStateException("Chroma 集合 [" + props.getCollection() + "] 不存在：无法索引");
        }
        List<String> ids = new ArrayList<>(fragments.size());
        List<float[]> vectors = new ArrayList<>(fragments.size());
        List<String> documents = new ArrayList<>(fragments.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(fragments.size());
        for (RagFragment f : fragments) {
            if (f.text() == null || f.text().isBlank()) {
                continue;
            }
            ids.add(chunkId(f.text(), f.source()));
            vectors.add(embedding.embed(f.text()));
            documents.add(f.text());
            metadatas.add(metadataOf(f));
        }
        guard.call(() -> {
            client.upsert(collectionRef.id(), ids, vectors, documents, metadatas);
            return null;
        });
        log.info("Chroma upsert 完成：collection={} count={}", props.getCollection(), ids.size());
    }

    /**
     * 按 metadata.source 精确删除（[[kb-ingest-design]]）：集合缺失 = 本来就没有可删内容 → 0；
     * Chroma 故障经熔断守卫快速失败（录入服务 best-effort 告警不阻塞）。
     */
    @Override
    public int deleteBySources(java.util.Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        ChromaRestClient.CollectionRef collectionRef = ensureRef();
        if (collectionRef == null) {
            log.info("Chroma 集合 [{}] 不存在，无可删内容：sources={}", props.getCollection(), sources.size());
            return 0;
        }
        guard.call(() -> client.deleteBySources(collectionRef.id(), sources));
        log.info("Chroma 按来源删除完成：collection={} sources={}", props.getCollection(), sources.size());
        return -1;
    }

    /** 懒解析集合引用（null 时每次调用重试——入库完成后自动恢复，启动缺失不阻塞）。 */
    private ChromaRestClient.CollectionRef ensureRef() {
        ChromaRestClient.CollectionRef current = ref;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (ref == null) {
                ChromaRestClient.CollectionRef resolved = client.resolveCollection(props.getCollection());
                if (resolved != null) {
                    log.info("Chroma 集合已解析：name={} id={} dimension={}",
                            resolved.name(), resolved.id(), resolved.dimension());
                }
                ref = resolved;
            }
            return ref;
        }
    }

    /** 确定性 chunk id：sha256(text#source)[:32]（与入库流水线同口径，幂等 upsert 不产生重复块；Lucene 同步复用）。 */
    public static String chunkId(String text, String source) {
        String payload = text + "#" + (source != null ? source : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** metadata 契约（与入库流水线对齐的键名子集；Java 运行期注入无需 page/sheet 等文档版面字段）。 */
    private static Map<String, Object> metadataOf(RagFragment f) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (f.source() != null) {
            meta.put("source", f.source());
        }
        if (f.domain() != null) {
            meta.put("domain", f.domain());
        }
        if (f.temporalTag() != null) {
            meta.put("temporal_tag", f.temporalTag());
        }
        if (f.validUntil() != null) {
            meta.put("valid_until", f.validUntil().toString());
        }
        if (f.timestamp() != null) {
            meta.put("updated", f.timestamp().toString());
        }
        meta.put("kind", "text");
        meta.put("doc_type", "runtime");
        return meta;
    }

    private static String metaString(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return (v != null && !String.valueOf(v).isBlank()) ? String.valueOf(v) : null;
    }

    /**
     * 时间解析：兼容 Instant 全格式与入库流水线的日期格式（{@code yyyy-MM-dd}，按 UTC 当日起算）。
     * 解析失败返 null（时效字段缺失 = 按当前片段处理；Lucene 同步复用）。
     */
    public static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 落日期格式解析
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
