package com.agentdemo007.persistence.repository;

import com.agentdemo007.persistence.entity.KbChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 知识库切块仓库（[[kb-ingest-design]]·任务3）。
 *
 * <p>核心查询：按 docUid+version 取精确 source 清单（换版/下架时删向量——Chroma 走
 * {@code source $in} 精确删除、InMemory/Lucene 同口径），按文档取切块（详情页/预览）。
 */
public interface KbChunkRepository extends JpaRepository<KbChunkEntity, Long> {

    /** 文档切块（详情页，序号升序）。 */
    List<KbChunkEntity> findByDocumentIdOrderBySeq(Long documentId);

    /** 指定版本的切块（删向量时据此重放精确 source 清单）。 */
    List<KbChunkEntity> findByDocUidAndVersion(String docUid, int version);

    /** 删除文档全部切块（下架清库；调用方 @Transactional）。 */
    @Modifying
    @Query("delete from KbChunkEntity c where c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
}
