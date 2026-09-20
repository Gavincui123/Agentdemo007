package com.agentdemo007.persistence.repository;

import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.persistence.entity.HitlTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 人工工单仓库（L2 挂起-恢复·2026-09-18）：工单持久副本的读回源入口——
 * 按工单 id（重启后恢复入口）、按幂等键取最新单（跨会话锚定 + TIMEOUT 重建语义）、
 * 按 PENDING 状态列待审批列表（管理台重启后不丢单）。
 */
public interface HitlTicketRepository extends JpaRepository<HitlTicketEntity, Long> {

    Optional<HitlTicketEntity> findByTicketId(String ticketId);

    /** 按业务幂等键查工单，创建时间倒序（首元素=最新单，对齐内存键指向最新单语义）。 */
    List<HitlTicketEntity> findByIdempotencyKeyOrderByCreatedAtDesc(String idempotencyKey);

    List<HitlTicketEntity> findByStatus(HumanTicket.Status status);
}
