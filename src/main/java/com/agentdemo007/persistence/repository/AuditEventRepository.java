package com.agentdemo007.persistence.repository;

import com.agentdemo007.persistence.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计事件持久化 Repository（Phase 13）。
 *
 * <p>{@code AuditConsumer} 消费 {@code audit.event} 消息后经此落库 {@link AuditEventEntity}。
 * Spring Data JPA 自动生成实现；独立审计链路，关键事件全量留痕（§5.11）。
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {
}
