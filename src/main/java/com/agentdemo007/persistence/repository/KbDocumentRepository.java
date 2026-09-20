package com.agentdemo007.persistence.repository;

import com.agentdemo007.capability.kb.KbNamespace;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文档仓库（[[kb-ingest-design]]·任务3）。
 *
 * <p>关键查询：业务键最新版（版本自增依据）、同键历史版本（换版时批量 SUPERSEDED + 清向量）、
 * 状态过滤列表（管理台）。
 */
public interface KbDocumentRepository extends JpaRepository<KbDocumentEntity, Long> {

    /** 业务键（namespace+docNo）最新版本（版本自增依据；无 → 空即 v1）。 */
    Optional<KbDocumentEntity> findTopByNamespaceAndDocNoOrderByVersionDesc(KbNamespace namespace, String docNo);

    /** 业务键历史版本（换版时将旧 ACTIVE 置 SUPERSEDED 并删其向量）。 */
    List<KbDocumentEntity> findByNamespaceAndDocNoAndStatus(KbNamespace namespace, String docNo,
                                                            KbDocumentEntity.Status status);

    /** 业务键全版本历史（版本时间线展示）。 */
    List<KbDocumentEntity> findByNamespaceAndDocNoOrderByVersionDesc(KbNamespace namespace, String docNo);

    /** 按状态列表（管理台：ACTIVE 台账 / 全量含历史）。 */
    List<KbDocumentEntity> findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status status);

    /** 全量列表（时间倒序）。 */
    List<KbDocumentEntity> findAllByOrderByCreatedAtDesc();
}
