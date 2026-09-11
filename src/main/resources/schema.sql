-- =============================================================================
-- Agentdemo007 — 数据库初始化脚本（Phase 13 持久化层）
-- =============================================================================
-- 适用：prod（MySQL 8+）。配合 application-prod.yml 的
--       spring.sql.init.mode=always 启动时执行；ddl-auto=none，本脚本是 prod
--       建表的唯一来源（项目未集成 Flyway/Liquibase）。
--
-- 幂等：全部用 CREATE TABLE IF NOT EXISTS，sql.init.mode=always 下反复启动安全。
--      切勿在此追加非幂等语句（ALTER/INSERT）；后续结构演进请引入迁移工具。
--
-- 类型对齐 JPA 实体（@Column 逐字段映射），并与 Hibernate 6 MySQL 方言默认一致：
--   - String + length      → VARCHAR(length)
--   - String columnDef=TEXT → TEXT
--   - boolean (primitive)  → BIT(1)        （Hibernate MySQL 默认）
--   - Long (@Id IDENTITY)  → BIGINT AUTO_INCREMENT
--   - OffsetDateTime / Instant → TIMESTAMP(6)  （Hibernate MySQL 默认；UTC 存取，
--       对齐 application.yml 的 hibernate.jdbc.time_zone=UTC）
--
-- dev：H2(MODE=MySQL) 走 ddl-auto=update 由 Hibernate 自建表，sql.init.mode=embedded
--      执行本脚本时表已存在 → IF NOT EXISTS 跳过，零冲突。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 会话轮次（Phase 13·异步落库）：MQ 消费者 HistoryPersistConsumer 收 ChatTurnEvent 落库
-- 对齐 ChatTurnEntity
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_turn (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    trace_id        VARCHAR(64)   NOT NULL,
    session_id      VARCHAR(64)   NOT NULL,
    raw_input       TEXT,
    final_reply     TEXT,
    intent          VARCHAR(32),
    degraded        BIT(1)        NOT NULL,
    scenario        VARCHAR(64),
    turn_timestamp  TIMESTAMP(6)  NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 审计事件（Phase 13·独立审计链路）：MQ 消费者 AuditConsumer 收 AuditEvent 落库
-- 对齐 AuditEventEntity；event_type 存枚举名（@Enumerated(STRING)）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_event (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    event_type      VARCHAR(32)   NOT NULL,
    trace_id        VARCHAR(64)   NOT NULL,
    session_id      VARCHAR(64),
    detail          TEXT,
    event_timestamp TIMESTAMP(6)  NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 推荐索引（MySQL 不支持 CREATE INDEX IF NOT EXISTS，故不纳入 always 幂等脚本；
--            首次建表后由 DBA 手动执行，或在引入迁移工具后纳入版本管理）
-- =============================================================================
-- 管理台「会话历史回放」按 session_id 检索、按 turn_timestamp 排序：
--   CREATE INDEX idx_chat_turn_session ON chat_turn (session_id, turn_timestamp);
-- 审计按 trace_id 关联全链路、按 created_at 留存扫描：
--   CREATE INDEX idx_audit_event_trace  ON audit_event (trace_id);
--   CREATE INDEX idx_audit_event_created ON audit_event (created_at);
-- =============================================================================
