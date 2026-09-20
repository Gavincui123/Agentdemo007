package com.agentdemo007.admin;

import com.agentdemo007.capability.kb.KbIngestService;
import com.agentdemo007.capability.kb.KbIngestService.KbIngestCommand;
import com.agentdemo007.capability.kb.KbIngestService.KbIngestResult;
import com.agentdemo007.capability.kb.KbNamespace;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.persistence.entity.KbChunkEntity;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 管理台·知识库录入端点（[[kb-ingest-design]]·任务2/3）。
 *
 * <ul>
 *   <li>{@code POST /admin/kb/preview} — 试运行：解析→清洗→切块预览，<b>不触库不索引</b>
 *      （前端确认后携 dryRun=false 重传同文件正式入库——无临时文件态，幂等安全）；</li>
 *   <li>{@code POST /admin/kb/documents} — 正式录入（同 namespace+docNo 重灌即换版：旧版
 *       SUPERSEDED + 向量删除，检索只见最新版）；</li>
 *   <li>{@code GET /admin/kb/documents} — 文档台账（namespace/status 过滤）；</li>
 *   <li>{@code GET /admin/kb/documents/{id}} — 文档详情 + 全部切块；</li>
 *   <li>{@code DELETE /admin/kb/documents/{id}} — 下架（幂等）：删向量 + DELETED + 快照移除。</li>
 * </ul>
 * 对外统一 {@link UnifiedResponse}；鉴权经 {@code AdminAuthInterceptor}（/admin/**）；
 * 录入业务异常（类型不支持/不可提取/解析失败）话术化转 {@link ErrorCode#BAD_REQUEST}。
 */
@RestController
@RequestMapping("/admin/kb")
public class KbAdminController {

    private final KbIngestService ingestService;
    private final KbDocumentRepository documents;

    public KbAdminController(KbIngestService ingestService, KbDocumentRepository documents) {
        this.ingestService = ingestService;
        this.documents = documents;
    }

    /** 试运行（解析/清洗/切块预览，不入库）。 */
    @PostMapping("/preview")
    public UnifiedResponse preview(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "docNo", required = false) String docNo,
                                   @RequestParam(value = "title", required = false) String title,
                                   @RequestParam(value = "namespace", defaultValue = "PUBLIC") String namespace,
                                   @RequestParam(value = "allowedPrincipals", required = false) String allowedPrincipals,
                                   @RequestParam(value = "domain", required = false) String domain,
                                   @RequestParam(value = "versionNote", required = false) String versionNote) {
        try {
            KbIngestResult result = ingestService.ingest(command(file, docNo, title, namespace,
                    allowedPrincipals, domain, versionNote, true));
            return UnifiedResponse.success(result);
        } catch (KbIngestService.KbIngestException e) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST, "文档解析失败：" + e.getMessage());
        }
    }

    /** 正式录入（嵌入入库 + 版本收口 + 目录快照刷新）。 */
    @PostMapping("/documents")
    public UnifiedResponse ingest(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "docNo", required = false) String docNo,
                                  @RequestParam(value = "title", required = false) String title,
                                  @RequestParam(value = "namespace", defaultValue = "PUBLIC") String namespace,
                                  @RequestParam(value = "allowedPrincipals", required = false) String allowedPrincipals,
                                  @RequestParam(value = "domain", required = false) String domain,
                                  @RequestParam(value = "versionNote", required = false) String versionNote) {
        try {
            KbIngestResult result = ingestService.ingest(command(file, docNo, title, namespace,
                    allowedPrincipals, domain, versionNote, false));
            return UnifiedResponse.success(result);
        } catch (KbIngestService.KbIngestException e) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            return UnifiedResponse.error(ErrorCode.BAD_REQUEST, "文件读取失败：" + e.getMessage());
        } catch (Exception e) {
            return UnifiedResponse.error(ErrorCode.INTERNAL_ERROR, "知识库录入失败（嵌入服务不可用？），请稍后重试");
        }
    }

    /** 文档台账（namespace/status 过滤；全量时间倒序）。 */
    @GetMapping("/documents")
    public UnifiedResponse list(@RequestParam(value = "namespace", required = false) String namespace,
                                @RequestParam(value = "status", required = false) String status) {
        List<KbDocumentEntity> docs;
        if (status != null && !status.isBlank()) {
            docs = documents.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.valueOf(status));
        } else {
            docs = documents.findAllByOrderByCreatedAtDesc();
        }
        if (namespace != null && !namespace.isBlank()) {
            KbNamespace ns = KbNamespace.valueOf(namespace);
            docs = docs.stream().filter(d -> d.getNamespace() == ns).toList();
        }
        return UnifiedResponse.success(docs.stream().map(KbAdminController::toSummary).toList());
    }

    /** 文档详情（含全部切块——预览面包屑/正文/长度）。 */
    @GetMapping("/documents/{id}")
    public UnifiedResponse detail(@PathVariable Long id) {
        return documents.findById(id)
                .<UnifiedResponse>map(doc -> UnifiedResponse.success(new KbDocumentDetail(
                        toSummary(doc),
                        ingestService.chunksOf(id).stream().map(KbAdminController::toChunkView).toList())))
                .orElseGet(() -> UnifiedResponse.error(ErrorCode.NOT_FOUND, "文档不存在：" + id));
    }

    /** 下架（幂等）：删向量 + 状态 DELETED + 目录快照移除。 */
    @DeleteMapping("/documents/{id}")
    public UnifiedResponse delete(@PathVariable Long id) {
        try {
            int removed = ingestService.delete(id);
            return UnifiedResponse.success(new KbDeleteResult(id, removed));
        } catch (KbIngestService.KbIngestException e) {
            return UnifiedResponse.error(ErrorCode.NOT_FOUND, e.getMessage());
        }
    }

    // ---- 请求/投影装配 ----

    private static KbIngestCommand command(MultipartFile file, String docNo, String title, String namespace,
                                           String allowedPrincipals, String domain, String versionNote,
                                           boolean dryRun) throws IOException {
        KbNamespace ns;
        try {
            ns = KbNamespace.valueOf((namespace == null || namespace.isBlank()) ? "PUBLIC" : namespace);
        } catch (IllegalArgumentException e) {
            throw new KbIngestService.KbIngestException("命名空间非法：" + namespace + "（仅 PUBLIC/PRIVATE）");
        }
        return new KbIngestCommand(file.getOriginalFilename(), file.getBytes(), docNo, title,
                ns, allowedPrincipals, domain, versionNote, null, dryRun);
    }

    /** 文档台账/详情投影（强类型 record，④收口非 Map）。 */
    public record KbDocumentSummary(Long id, String docNo, String title, String namespace,
                                    String allowedPrincipals, String docType, String domain,
                                    int version, String status, int chunkCount, int charCount,
                                    String versionNote, String createdBy, String createdAt,
                                    String supersededAt) {
    }

    /** 切块投影。 */
    public record KbChunkView(int seq, String heading, String text, int charCount, String createdAt) {
    }

    public record KbDocumentDetail(KbDocumentSummary document, List<KbChunkView> chunks) {
    }

    public record KbDeleteResult(Long id, int removedVectorCount) {
    }

    private static KbDocumentSummary toSummary(KbDocumentEntity d) {
        return new KbDocumentSummary(d.getId(), d.getDocNo(), d.getTitle(),
                d.getNamespace().name(), d.getAllowedPrincipals(), d.getDocType(), d.getDomain(),
                d.getVersion(), d.getStatus().name(), d.getChunkCount(), d.getCharCount(),
                d.getVersionNote(), d.getCreatedBy(),
                d.getCreatedAt() == null ? null : d.getCreatedAt().toString(),
                d.getSupersededAt() == null ? null : d.getSupersededAt().toString());
    }

    private static KbChunkView toChunkView(KbChunkEntity c) {
        return new KbChunkView(c.getSeq(), c.getHeadingPath(), c.getText(), c.getCharCount(),
                c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
    }
}
