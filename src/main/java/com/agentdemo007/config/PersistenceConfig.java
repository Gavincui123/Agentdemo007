package com.agentdemo007.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 持久化审计配置（Phase 13·持久化收口）。
 *
 * <p>启用 JPA 审计（{@link EnableJpaAuditing}）：会话/审计实体上的 {@code @CreatedDate} 落库时间戳
 * 由 {@code AuditingEntityListener} 在 {@code @PrePersist} 自动填充——与事件逻辑时间戳
 * （{@code timestamp}，由 MQ 载荷携带、记录事件发生时刻）区分，单独记录"何时写入 DB"，
 * 满足 §5.11 不可篡改留存（append-only 审计行 + 独立落库时刻，便于对账与篡改检测）。
 *
 * <p>持久化模块的显式配置锚点（镜像 {@link RabbitMqConfig}/{@code RedisConfig} 的条件装配收口）；
 * 数据源与 {@code ddl-auto} 仍由 {@code application-{profile}.yml} 管理（H2 dev / MySQL prod），
 * 本类仅收口审计行为，不重复自动配置的职责。
 */
@Configuration
@EnableJpaAuditing
public class PersistenceConfig {
}
