package com.agentdemo007.persistence.entity;

import com.agentdemo007.capability.kb.KbNamespace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 知识库文档实体（[[kb-ingest-design]]·任务3 元数据持久层）。
 *
 * <p>业务键 = namespace + docNo，version 在其上单调自增：<b>同键重灌 → 版本 +1</b>，旧版本置
 * SUPERSEDED（向量库中旧片段同步删除，检索只见最新版；DB 保留全版本历史可回溯）。
 * 版本链（SUPERSEDED）+ 删除（DELETED）均为软态——向量库即时生效，历史在 DB 永存。
 *
 * <p>权限模型（Phase 21 三轴拆分）：{@code namespace} 管内外边界（PUBLIC 人人可检索 / PRIVATE 仅
 * {@code allowedPrincipals} 名单主体可检索）；{@code requiredLevel} 管客户等级可见性（安全轴·权限主载体，
 * 检索主体等级 ≥ 文档要求档）；{@code domain} 管业务分类（路由窄化，不在本实体权限语义内）。
 * {@code allowedPrincipals} 为点对点例外通道。检索侧经 {@code KbCatalogService} 内存快照过滤（不逐片段查库）。
 * 非 {@code final}、protected 无参构造满足 JPA 代理要求（镜像 {@link HitlTicketEntity} 范式）。
 */
@Entity
@Table(name = "kb_document",
        indexes = {
                @Index(name = "idx_kb_document_key", columnList = "namespace,doc_no,version"),
                @Index(name = "idx_kb_document_status", columnList = "status")
        })
public class KbDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务文档号（同键重灌即升版本；消毒后不含空白/分隔符）。 */
    @Column(name = "doc_no", nullable = false, length = 128)
    private String docNo;

    /** 命名空间（公开/私有；存枚举名）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "namespace", nullable = false, length = 16)
    private KbNamespace namespace;

    /** 私有命名空间可检索主体名单（逗号分隔；PUBLIC 忽略）。Phase 21 降为例外通道（点对点授权），不承载主权限。 */
    @Column(name = "allowed_principals", length = 512)
    private String allowedPrincipals;

    /** 客户等级可见性（Phase 21 安全轴·权限主载体）：存 {@link com.agentdemo007.capability.kb.KbLevel} 档位 int，
     *  检索谓词 = 主体等级 ≥ 此档（allowedPrincipals 命中例外优先）。NOT NULL DEFAULT 0 存量行平滑升级。 */
    @Column(name = "required_level", nullable = false)
    private int requiredLevel;

    /** 文档标题（默认取文件名去扩展名；切块 breadcrumb 首段）。 */
    @Column(name = "title", nullable = false, length = 256)
    private String title;

    /** 原始文件名。 */
    @Column(name = "file_name", length = 256)
    private String fileName;

    /** 文档类型（md/txt/pdf/docx/xlsx/html/csv/json）。 */
    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    /** 知识域（对齐 corpus metadata.domain，供 RoutePlan knowledgeDomains 窄化；可空）。 */
    @Column(name = "domain", length = 64)
    private String domain;

    /** 版本号（namespace+docNo 内自 1 单调递增）。 */
    @Column(name = "version", nullable = false)
    private int version;

    /** 生命周期：ACTIVE 检索可见 / SUPERSEDED 被新版替代 / DELETED 已下架。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** 文件 SHA-256（去重/审计）。 */
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    /** 切块数量。 */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** 清洗后正文总字符数。 */
    @Column(name = "char_count", nullable = false)
    private int charCount;

    /** 版本说明（本版改了什么，可空）。 */
    @Column(name = "version_note", length = 512)
    private String versionNote;

    /** 录入人（管理台令牌主体；可空）。 */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 被替代时刻（null=未被替代）。 */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** 生命周期状态机（append-only 演进：ACTIVE→{SUPERSEDED, DELETED}）。 */
    public enum Status { ACTIVE, SUPERSEDED, DELETED }

    /** JPA 代理要求的无参构造。 */
    protected KbDocumentEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getDocNo() {
        return docNo;
    }

    public KbNamespace getNamespace() {
        return namespace;
    }

    public String getAllowedPrincipals() {
        return allowedPrincipals;
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDocType() {
        return docType;
    }

    public String getDomain() {
        return domain;
    }

    public int getVersion() {
        return version;
    }

    public Status getStatus() {
        return status;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public int getCharCount() {
        return charCount;
    }

    public String getVersionNote() {
        return versionNote;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    // ---- 状态迁移写入口（收口：禁止旁路 set 漂移状态机）----

    public void markSuperseded(Instant at) {
        this.status = Status.SUPERSEDED;
        this.supersededAt = at;
    }

    public void markDeleted() {
        this.status = Status.DELETED;
    }

    // ---- 装配器（录入服务专用；字段一次成形）----

    /** 既有调用方兼容（requiredLevel=V0；新链路一律走全参装配器——录入必填校验在服务层）。 */
    public static KbDocumentEntity create(KbNamespace namespace, String docNo, String allowedPrincipals,
                                          String title, String fileName, String docType, String domain,
                                          int version, String checksum, int chunkCount, int charCount,
                                          String versionNote, String createdBy, Instant createdAt) {
        return create(namespace, docNo, allowedPrincipals, com.agentdemo007.capability.kb.KbLevel.V0,
                title, fileName, docType, domain, version, checksum, chunkCount, charCount,
                versionNote, createdBy, createdAt);
    }

    public static KbDocumentEntity create(KbNamespace namespace, String docNo, String allowedPrincipals,
                                          com.agentdemo007.capability.kb.KbLevel requiredLevel,
                                          String title, String fileName, String docType, String domain,
                                          int version, String checksum, int chunkCount, int charCount,
                                          String versionNote, String createdBy, Instant createdAt) {
        KbDocumentEntity e = new KbDocumentEntity();
        e.namespace = namespace;
        e.docNo = docNo;
        e.allowedPrincipals = allowedPrincipals;
        e.requiredLevel = (requiredLevel != null) ? requiredLevel.code() : 0;
        e.title = title;
        e.fileName = fileName;
        e.docType = docType;
        e.domain = domain;
        e.version = version;
        e.status = Status.ACTIVE;
        e.checksum = checksum;
        e.chunkCount = chunkCount;
        e.charCount = charCount;
        e.versionNote = versionNote;
        e.createdBy = createdBy;
        e.createdAt = createdAt;
        return e;
    }
}
