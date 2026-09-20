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

-- -----------------------------------------------------------------------------
-- HITL 挂起检查点（L2 挂起-恢复·2026-09-18）：HitlStep(610) 挂起即异步落库，
-- 审批通过后 HitlResumeService 读此快照恢复执行。DB 为持久真相源（Redis 热副本
-- hitl:checkpoint:{ticket_id} 另存一份，TTL 默认 24h）；状态机 ACTIVE/CONSUMED/EXPIRED。
-- 对齐 HitlCheckpointEntity；mock 数据见 docs/sql/hitl_l2_init.sql
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hitl_checkpoint (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    ticket_id       VARCHAR(64)   NOT NULL,
    idempotency_key VARCHAR(128)  NOT NULL,
    trace_id        VARCHAR(64),
    session_id      VARCHAR(64),
    status          VARCHAR(16)   NOT NULL,
    snapshot_json   TEXT,
    paused_at       TIMESTAMP(6)  NOT NULL,
    resumed_at      TIMESTAMP(6),
    resume_reply    TEXT,
    expire_reason   VARCHAR(256),
    created_at      TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- HITL 人工工单（L2 挂起-恢复·2026-09-18）：内存状态机为真相源（单实例），本表为持久副本
-- ——建单/决议经 hitl-ticket-writer 异步落库，重启后按 id/幂等键/PENDING 回源（审批不丢单）。
-- 对齐 HitlTicketEntity；mock 数据见 docs/sql/hitl_l2_init.sql
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hitl_ticket (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    ticket_id       VARCHAR(64)   NOT NULL,
    idempotency_key VARCHAR(128),
    session_id      VARCHAR(64),
    query           TEXT,
    reason          TEXT,
    status          VARCHAR(16)   NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    resolved_at     TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 业务订单（L2 HITL 业务前置校验·2026-09-18）：HitlBusinessGate 审批放行前对账订单真实
-- 状态——退款查 payment_status（仅 PAID 放行）、退货查 logistics_status（已签收放行）。
-- 自然主键 order_id（业务单号）；状态列为 String（mock/真系统接入免迁移扩展）。
-- 对齐 BizOrderEntity；<b>必须配 mock 数据</b>见 docs/sql/hitl_l2_init.sql（无数据=审批 fail-closed）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz_order (
    order_id         VARCHAR(64)   NOT NULL,
    user_id          VARCHAR(64),
    payment_status   VARCHAR(32),
    logistics_status VARCHAR(32),
    refund_status    VARCHAR(32),
    amount           DECIMAL(12,2),
    updated_at       TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- -----------------------------------------------------------------------------
-- 知识库文档（[[kb-ingest-design]]·任务3 元数据）：/admin/kb 录入通道。
-- 业务键 = namespace + doc_no，version 单调自增：重灌即换版（旧版 SUPERSEDED + 向量删除，
-- 检索只见最新版，DB 保留全版本历史）。权限：PUBLIC 人人可检索；PRIVATE 仅
-- allowed_principals 名单主体（对话侧 PipelineContext.userId）。对齐 KbDocumentEntity。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_document (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    doc_no             VARCHAR(128)  NOT NULL,
    namespace          VARCHAR(16)   NOT NULL,
    allowed_principals VARCHAR(512),
    title              VARCHAR(256)  NOT NULL,
    file_name          VARCHAR(256),
    doc_type           VARCHAR(16)   NOT NULL,
    domain             VARCHAR(64),
    version            INT           NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    checksum           VARCHAR(64)   NOT NULL,
    chunk_count        INT           NOT NULL,
    char_count         INT           NOT NULL,
    version_note       VARCHAR(512),
    created_by         VARCHAR(64),
    created_at         TIMESTAMP(6)  NOT NULL,
    superseded_at      TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 知识库切块（[[kb-ingest-design]]·任务3）：片段级持久副本 + 向量删除依据。
-- source = {doc_uid}:v{version}#{seq} 与向量库逐条对应（KbSourceRef 编解码）；
-- doc_uid+version 支撑换版/下架时精确 source 清单删向量。对齐 KbChunkEntity。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_chunk (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    document_id     BIGINT        NOT NULL,
    doc_uid         VARCHAR(256)  NOT NULL,
    version         INT           NOT NULL,
    seq             INT           NOT NULL,
    heading_path    VARCHAR(512),
    text            TEXT,
    char_count      INT           NOT NULL,
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
-- HITL 工单按幂等键锚定查询（跨会话防重）/按状态列待审批：
--   CREATE INDEX idx_hitl_ticket_key    ON hitl_ticket (idempotency_key);
--   CREATE INDEX idx_hitl_ticket_status ON hitl_ticket (status);
-- HITL 检查点按幂等键审计追溯：
--   CREATE INDEX idx_hitl_checkpoint_key ON hitl_checkpoint (idempotency_key);
-- =============================================================================
