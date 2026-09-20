-- =============================================================================
-- Agentdemo007 — HITL L2 挂起-恢复：建表 SQL + mock 数据（2026-09-18 用户裁决）
-- =============================================================================
-- 用途：用户自建三张表 + 灌 mock 数据（退款查支付状态 / 退货查物流状态 / 工单 / 检查点）。
--
-- 执行：mysql -h <host> -u <user> -p agent_dev < docs/sql/hitl_l2_init.sql
--       （可重复执行：建表 IF NOT EXISTS；mock 数据先 DELETE 后 INSERT，幂等）
--
-- 与项目其他建表途径的关系：
--   1. src/main/resources/schema.sql 含同结构幂等 DDL（JPA_DDL=none 的 prod 启动自动执行，
--      只有表没有数据）——本文件与其结构逐列一致，谁先执行都兼容；
--   2. dev/默认 JPA_DDL=update 时 Hibernate 也会自动建表——本文件的 mock INSERT 照常可用。
--
-- 三张表职责（对应四点裁决）：
--   hitl_checkpoint  挂起检查点持久副本（DB 真相源；Redis 热副本 hitl:checkpoint:{ticket_id}，
--                    TTL 默认 24h，由 app.redis.enabled=true 时自动写入）；
--   hitl_ticket      人工工单持久副本（异步落库，重启后按 id/幂等键/PENDING 回源，审批不丢单）；
--   biz_order        业务订单（工单收尾不自证：退款对账 payment_status、退货对账 logistics_status，
--                    fail-closed——订单不存在/状态不满足一律拒绝放行）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. HITL 挂起检查点（对齐 HitlCheckpointEntity）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hitl_checkpoint (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    ticket_id       VARCHAR(64)   NOT NULL,
    idempotency_key VARCHAR(128)  NOT NULL,
    trace_id        VARCHAR(64),
    session_id      VARCHAR(64),
    status          VARCHAR(16)   NOT NULL COMMENT 'ACTIVE/CONSUMED/EXPIRED',
    snapshot_json   TEXT          COMMENT 'HitlCheckpointSnapshot JSON',
    paused_at       TIMESTAMP(6)  NOT NULL,
    resumed_at      TIMESTAMP(6),
    resume_reply    TEXT,
    expire_reason   VARCHAR(256),
    created_at      TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 2. HITL 人工工单（对齐 HitlTicketEntity）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hitl_ticket (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    ticket_id       VARCHAR(64)   NOT NULL,
    idempotency_key VARCHAR(128)  COMMENT 'hitl:{action}:{订单号}',
    session_id      VARCHAR(64),
    query           TEXT,
    reason          TEXT,
    status          VARCHAR(16)   NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/TIMEOUT',
    created_at      TIMESTAMP(6)  NOT NULL,
    resolved_at     TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 3. 业务订单（对齐 BizOrderEntity；自然主键 = 业务单号）
--    payment_status:   UNPAID / PAID / REFUNDING / REFUNDED
--    logistics_status: NOT_SHIPPED / SHIPPED / DELIVERED / RETURN_IN_TRANSIT / RETURN_RECEIVED
--    refund_status:    NONE / APPLYING / APPROVED / REFUNDED / REJECTED
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

-- =============================================================================
-- mock 数据（幂等：先清后插）
-- =============================================================================

-- ---- 业务订单：覆盖各状态组合，供业务门放行/拒绝矩阵演示 ----
DELETE FROM biz_order WHERE order_id IN
  ('ORD-001', 'ORD-20240901-001', 'ORD-20240901-002', 'ORD-20240901-003', 'ORD-20240901-004', 'ORD-20240901-005');
INSERT INTO biz_order (order_id, user_id, payment_status, logistics_status, refund_status, amount, updated_at) VALUES
  -- 退款✓（已支付）+ 退货✓（已签收）
  ('ORD-001',            'U1001', 'PAID',     'DELIVERED',  'NONE',     199.00, '2026-09-17 12:00:00'),
  ('ORD-20240901-001',   'U1001', 'PAID',     'DELIVERED',  'NONE',    1299.00, '2026-09-17 12:00:00'),
  -- 退款✓（已支付）；退货✗（未发货）
  ('ORD-20240901-002',   'U1002', 'PAID',     'NOT_SHIPPED','NONE',     299.00, '2026-09-17 12:00:00'),
  -- 退款✗（未支付）
  ('ORD-20240901-003',   'U1003', 'UNPAID',   'NOT_SHIPPED','NONE',      59.00, '2026-09-17 12:00:00'),
  -- 退货✗（在途，签收后可退）
  ('ORD-20240901-004',   'U1004', 'PAID',     'SHIPPED',    'NONE',     459.00, '2026-09-17 12:00:00'),
  -- 退款✗（已退款，不可重复退）
  ('ORD-20240901-005',   'U1005', 'REFUNDED', 'DELIVERED',  'REFUNDED', 899.00, '2026-09-17 12:00:00');

-- ---- 人工工单：1 张 PENDING（待审批演示）+ 2 张 APPROVED（含已恢复历史）----
DELETE FROM hitl_ticket WHERE ticket_id IN ('t-demo-0001', 't-demo-0002', 't-demo-0003');
INSERT INTO hitl_ticket (ticket_id, idempotency_key, session_id, query, reason, status, created_at, resolved_at) VALUES
  ('t-demo-0001', 'hitl:REFUND:ORD-20240901-002', 'sess-demo-003',
   'ORD-20240901-002 我要退款', '高风险退款申请，转人工确认', 'PENDING',
   '2026-09-18 10:00:00', NULL),
  ('t-demo-0002', 'hitl:RETURN:ORD-20240901-001', 'sess-demo-001',
   'ORD-20240901-001 我要退货', '高风险退货申请，转人工确认', 'APPROVED',
   '2026-09-18 10:05:00', '2026-09-18 10:30:00'),
  ('t-demo-0003', 'hitl:REFUND:ORD-20240901-001', 'sess-demo-002',
   'ORD-20240901-001 申请退款', '高风险退款申请，转人工确认', 'APPROVED',
   '2026-09-18 09:05:00', '2026-09-18 09:40:00');

-- ---- 挂起检查点：t-demo-0002 为 ACTIVE（重启后可走"显式恢复接口"重驱）；t-demo-0003 为 CONSUMED 历史 ----
DELETE FROM hitl_checkpoint WHERE ticket_id IN ('t-demo-0002', 't-demo-0003');
INSERT INTO hitl_checkpoint
  (ticket_id, idempotency_key, trace_id, session_id, status, snapshot_json, paused_at, resumed_at, resume_reply, expire_reason, created_at) VALUES
  ('t-demo-0002', 'hitl:RETURN:ORD-20240901-001', 'trace-demo-0002', 'sess-demo-001', 'ACTIVE',
   '{"ticketId":"t-demo-0002","idempotencyKey":"hitl:RETURN:ORD-20240901-001","traceId":"trace-demo-0002","sessionId":"sess-demo-001","userId":"10086","rawInput":"ORD-20240901-001 我要退货","standardQueryText":"ORD-20240901-001 退货","intentName":"TRANSFER_TO_HUMAN","routePlanJson":null,"pausedAtMs":1789697100000}',
   '2026-09-18 10:05:00', NULL, NULL, NULL, '2026-09-18 10:05:00'),
  ('t-demo-0003', 'hitl:REFUND:ORD-20240901-001', 'trace-demo-0003', 'sess-demo-002', 'CONSUMED',
   '{"ticketId":"t-demo-0003","idempotencyKey":"hitl:REFUND:ORD-20240901-001","traceId":"trace-demo-0003","sessionId":"sess-demo-002","userId":"10086","rawInput":"ORD-20240901-001 申请退款","standardQueryText":"ORD-20240901-001 退款","intentName":"TRANSFER_TO_HUMAN","routePlanJson":null,"pausedAtMs":1789693500000}',
   '2026-09-18 09:05:00', '2026-09-18 09:40:00', '已批准：退款已提交，预计 1-3 个工作日到账', NULL, '2026-09-18 09:05:00');

-- =============================================================================
-- 建议索引（表已存在时补建；CREATE TABLE 路径不含二级索引）
-- =============================================================================
-- CREATE INDEX idx_hitl_ticket_key        ON hitl_ticket (idempotency_key);
-- CREATE INDEX idx_hitl_ticket_status     ON hitl_ticket (status);
-- CREATE INDEX idx_hitl_checkpoint_key    ON hitl_checkpoint (idempotency_key);

-- =============================================================================
-- 演示走查（与 mock 数据配套）
-- =============================================================================
-- ① 重启不丢（工单 DB 回源）：重启应用 → GET /admin/hitl/tickets 仍列出 t-demo-0001（PENDING）；
-- ② 业务门放行矩阵：
--    - confirm t-demo-0001（退款 ORD-20240901-002，PAID）→ 放行 → 触发异步恢复；
--    - 若把 ORD-20240901-002 改成 UNPAID 再建新单 → confirm 返回 409「订单未支付，不满足退款前提」；
--    - 退货工单（hitl:RETURN:*）对 ORD-20240901-004（SHIPPED）→ 409「在途，签收后方可退货」。
-- ③ 严格锚点匹配（多工单）：检查点幂等键 != 工单幂等键 → 检查点作废（status=EXPIRED +
--    expire_reason 留痕），恢复拒绝——允许多张工单并存，但每单只消费与自身键一致的检查点。
-- ④ 显式恢复接口：POST /admin/hitl/tickets/t-demo-0002/resume（APPROVED + ACTIVE 检查点）
--    → 重驱 610 后段流水线 → 会话 sess-demo-001 出现恢复回复 → checkpoint 转 CONSUMED。
-- =============================================================================
