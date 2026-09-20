package com.agentdemo007.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 知识库切块实体（[[kb-ingest-design]]·任务3：片段级持久副本 + 向量删除依据）。
 *
 * <p>每个切块对应向量库一条记录（{@code source = {docUid}:v{version}#{seq}}，与
 * {@code KbSourceRef} 编解码一致）；docUid+version 列支撑"换版/下架时按精确 source 清单
 * 删向量"。切块正文随文档版本共存亡（文档 SUPERSEDED/DELETED 后仅作历史，不再被检索——
 * 向量库已同步删除）。protected 无参构造满足 JPA 代理要求。
 */
@Entity
@Table(name = "kb_chunk",
        indexes = {
                @Index(name = "idx_kb_chunk_doc", columnList = "document_id,seq"),
                @Index(name = "idx_kb_chunk_source", columnList = "doc_uid,version")
        })
public class KbChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档行 id（KbDocumentEntity.id）。 */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 文档级标识（kb:{ns}:{docNo}，与向量库 source 前缀对应）。 */
    @Column(name = "doc_uid", nullable = false, length = 256)
    private String docUid;

    /** 所属版本（与 document 同版）。 */
    @Column(name = "version", nullable = false)
    private int version;

    /** 版本内序号（1 起，与向量 source #{seq} 一致）。 */
    @Column(name = "seq", nullable = false)
    private int seq;

    /** 标题面包屑（"title › 章 › 节"，切块时已嵌入向量文本首行；此处供预览展示）。 */
    @Column(name = "heading_path", length = 512)
    private String headingPath;

    /** 切块正文（不含 breadcrumb）。 */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /** 正文长度（字符）。 */
    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 代理要求的无参构造。 */
    protected KbChunkEntity() {
    }

    public static KbChunkEntity create(Long documentId, String docUid, int version, int seq,
                                       String headingPath, String text, Instant createdAt) {
        KbChunkEntity c = new KbChunkEntity();
        c.documentId = documentId;
        c.docUid = docUid;
        c.version = version;
        c.seq = seq;
        c.headingPath = headingPath;
        c.text = text;
        c.charCount = text != null ? text.length() : 0;
        c.createdAt = createdAt;
        return c;
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getDocUid() {
        return docUid;
    }

    public int getVersion() {
        return version;
    }

    public int getSeq() {
        return seq;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public String getText() {
        return text;
    }

    public int getCharCount() {
        return charCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
