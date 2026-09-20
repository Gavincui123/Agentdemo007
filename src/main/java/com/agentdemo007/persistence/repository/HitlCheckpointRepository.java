package com.agentdemo007.persistence.repository;

import com.agentdemo007.persistence.entity.HitlCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * HITL 挂起检查点仓库（L2 挂起-恢复·2026-09-18）。{@code hitl_checkpoint} 表由
 * JPA {@code ddl-auto=update} 自动建表（dev H2 / prod MySQL），无需手工 DDL。
 */
public interface HitlCheckpointRepository extends JpaRepository<HitlCheckpointEntity, Long> {

    /** 按工单 id 查检查点（一对一）。 */
    Optional<HitlCheckpointEntity> findByTicketId(String ticketId);

    /** 按状态查（运维/审计：ACTIVE 待恢复清单）。 */
    List<HitlCheckpointEntity> findByStatus(HitlCheckpointEntity.Status status);
}
