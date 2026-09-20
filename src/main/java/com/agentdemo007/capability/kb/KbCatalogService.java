package com.agentdemo007.capability.kb;

import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 知识库目录快照（[[kb-ingest-design]]·任务3 权限过滤核心）。
 *
 * <p>检索侧权限过滤不能逐片段查库（RagStep 漏斗每请求池 20+ 片段）——本服务维护
 * {@code docUid → (namespace, allowedPrincipals, status)} 内存快照，启动全量加载、
 * 录入/下架增量刷新。过滤规则：
 * <ul>
 *   <li>非托管来源（无 {@code kb:} 前缀：dev 种子 / Python 流水线语料）→ <b>恒放行</b>
 *       （公开语义向后兼容，存量知识不因新权限模型消失）；</li>
 *   <li>托管片段：文档须 ACTIVE；PUBLIC 恒可读；PRIVATE 仅 {@code allowedPrincipals}
 *       含对话主体（{@code PipelineContext.userId}）可读；</li>
 *   <li>主体为 null（eval/未登录）→ 仅 PUBLIC 可读。</li>
 * </ul>
 * 管理台侧（录入/列表）直查 DB 不经此——快照只服务检索热路径。
 */
@Component
public class KbCatalogService {

    private static final Logger log = LoggerFactory.getLogger(KbCatalogService.class);

    private final KbDocumentRepository repository;

    /** docUid → 目录项快照（只含 ACTIVE 文档；换版/下架经 refresh/remove 增量维护）。 */
    private final Map<String, Entry> catalog = new ConcurrentHashMap<>();

    public KbCatalogService(KbDocumentRepository repository) {
        this.repository = repository;
    }

    private record Entry(KbNamespace namespace, Set<String> allowedPrincipals) {
    }

    @PostConstruct
    void loadAll() {
        try {
            for (KbDocumentEntity doc : repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE)) {
                put(doc);
            }
            log.info("知识库目录快照加载完成：ACTIVE 文档 {} 份", catalog.size());
        } catch (Exception e) {
            log.warn("知识库目录快照加载失败（检索过滤降级为仅非托管+公开口径，不影响启动）：reason={}", e.getMessage());
        }
    }

    /** 录入/换版后刷新（以 DB 最新行为准）。 */
    public void refresh(KbDocumentEntity doc) {
        if (doc == null) {
            return;
        }
        if (doc.getStatus() == KbDocumentEntity.Status.ACTIVE) {
            put(doc);
        } else {
            catalog.remove(KbSourceRef.docUid(doc.getNamespace(), doc.getDocNo()));
        }
    }

    /** 下架后移除（该 docUid 的所有版本片段一并失效）。 */
    public void remove(KbNamespace namespace, String docNo) {
        catalog.remove(KbSourceRef.docUid(namespace, docNo));
    }

    /**
     * 按对话主体过滤检索片段池（RagStep 漏斗②domain 窄化之后、粗滤之前调用）。
     *
     * @param pool     检索候选（source 携带 docUid）
     * @param userId   对话主体（null=匿名/eval，仅 PUBLIC 可见）
     * @return 主体可读的片段子集（保序）
     */
    public List<RagFragment> filterReadable(List<RagFragment> pool, String userId) {
        if (pool == null || pool.isEmpty()) {
            return pool;
        }
        return pool.stream().filter(f -> readable(f.source(), userId)).toList();
    }

    /** 单片段可读判定（规则见类注释）。 */
    public boolean readable(String source, String userId) {
        String docUid = KbSourceRef.docUidOf(source);
        if (docUid == null) {
            return true; // 非托管来源：种子/Python 流水线语料，公开语义
        }
        Entry entry = catalog.get(docUid);
        if (entry == null) {
            return false; // 托管但不在目录（已下架/私有无权见的兜底口径）
        }
        if (entry.namespace() == KbNamespace.PUBLIC) {
            return true;
        }
        return userId != null && entry.allowedPrincipals().contains(userId);
    }

    private void put(KbDocumentEntity doc) {
        Set<String> principals = (doc.getAllowedPrincipals() == null || doc.getAllowedPrincipals().isBlank())
                ? Set.of()
                : Arrays.stream(doc.getAllowedPrincipals().split("[,，;；]"))
                        .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        catalog.put(KbSourceRef.docUid(doc.getNamespace(), doc.getDocNo()),
                new Entry(doc.getNamespace(), principals));
    }
}
