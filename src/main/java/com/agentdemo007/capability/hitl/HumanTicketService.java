package com.agentdemo007.capability.hitl;

import com.agentdemo007.persistence.entity.HitlTicketEntity;
import com.agentdemo007.persistence.repository.HitlTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 人工工单服务（第四层·HITL 工单创建与管理；DB 持久副本·2026-09-18 用户裁决）。
 *
 * <p>触发 HITL 时 {@code createTicket} 挂起一张 PENDING 工单（带业务幂等键索引）；人工审批经
 * {@code resolve} 流转到 APPROVED/REJECTED（<b>状态机守卫：仅 PENDING 单向转移</b>，重复/反向
 * 决议不生效并显式回报）；超时经 {@code markTimeout} 流转到 TIMEOUT。
 *
 * <p><b>持久化分层</b>（用户裁决：工单落 DB，重启不丢；写入一律异步不阻塞主线）：
 * 内存 {@link ConcurrentHashMap} 为状态机真相源（单实例），单守护线程
 * {@code hitl-ticket-writer} 异步落 {@code hitl_ticket} 表（best-effort，失败仅告警）；
 * 读路径内存优先，未命中回源 DB 重建（重启后恢复入口）：按 id（{@link #findById}）、
 * 按幂等键取最新单（{@link #findByIdempotencyKey}）、按 PENDING 列表（{@link #pendingTickets}，
 * 管理台重启后不丢单）。<b>键指针只随"新建"与"按键查询"更新</b>——按 id/列表回源的旧单
 * 不得抢占键指针（TIMEOUT 重建后键指向新单的语义跨重启保持）。
 *
 * <p>幂等语义（2026-09-18 L2 配套·用户裁决）：
 * <ul>
 *   <li>建单幂等：{@code findByIdempotencyKey} 供 {@code HitlStep} 在建单前查询——同业务键
 *       （订单号+动作）已有 PENDING 单则复用、APPROVED 单则放行、REJECTED 单则拒绝，
 *       均不重复建单；TIMEOUT 单允许重建（键索引指向最新单）；</li>
 *   <li>决议幂等：{@code resolve} 只接受 PENDING 起点转移——同决议重复提交
 *       （双击/并发）报 {@link ResolveResult#IDEMPOTENT_REPEAT}（不重复触发恢复），
 *       反向决议报 {@link ResolveResult#CONFLICT}（防状态回翻/超时单复活）。</li>
 * </ul>
 * 收口：HITL 工单的数据真相源只经此服务；未知 id 解析幂等不抛异常（不阻塞主链路，§5.12 每步降级）。
 */
@Component
public class HumanTicketService {

    private static final Logger log = LoggerFactory.getLogger(HumanTicketService.class);

    private final ConcurrentHashMap<String, HumanTicket> tickets = new ConcurrentHashMap<>();
    /** 业务幂等键 → 最新工单 id（TIMEOUT 重建时指向新单；漂移校验据此判"键已指向别单"）。 */
    private final ConcurrentHashMap<String, String> idByKey = new ConcurrentHashMap<>();
    private final HitlTicketRepository repository; // 可空：无 JPA 环境（部分单测）内存降级
    private final Executor writer;

    /** 内存降级构造（既有测试/最小装配）。 */
    public HumanTicketService() {
        this(null, null);
    }

    /** Spring 装配：JPA 仓库缺席（受限切片）自动降级为内存实现。 */
    @Autowired
    public HumanTicketService(ObjectProvider<HitlTicketRepository> repository) {
        this(repository.getIfAvailable(), null);
    }

    /** 全参构造（测试可注入同步/受控执行器）。 */
    public HumanTicketService(HitlTicketRepository repository, Executor writer) {
        this.repository = repository;
        this.writer = (writer != null) ? writer : defaultWriter();
    }

    private static Executor defaultWriter() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hitl-ticket-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 决议结果（状态机守卫产出，调用方据此决定是否触发恢复/返回 409）。 */
    public enum ResolveResult {
        /** PENDING → 目标终态，转移成功（首次决议）。 */
        TRANSITIONED,
        /** 已是目标终态的重复提交（双击/并发重放）：幂等忽略，不重复触发副作用。 */
        IDEMPOTENT_REPEAT,
        /** 已被决议为其他终态（反向决议/超时后决议）：拒绝，状态不回翻。 */
        CONFLICT
    }

    /** 创建 PENDING 工单（兼容口径：无业务幂等键）。 */
    public HumanTicket createTicket(HitlRequest request, Instant createdAt) {
        return createTicket(request, createdAt, null);
    }

    /** 创建 PENDING 工单（带业务幂等键索引；key 可空=不参与幂等/恢复锚定）。 */
    public HumanTicket createTicket(HitlRequest request, Instant createdAt, String idempotencyKey) {
        HumanTicket ticket = new HumanTicket(
                UUID.randomUUID().toString(),
                request.sessionId(),
                request.query(),
                request.reason(),
                HumanTicket.Status.PENDING,
                createdAt,
                null,
                idempotencyKey);
        tickets.put(ticket.id(), ticket);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idByKey.put(idempotencyKey, ticket.id()); // TIMEOUT 重建场景：键指向最新单
        }
        persistAsync(ticket);
        return ticket;
    }

    /** 按工单 id 查询（内存优先，未命中回源 DB 重建——重启后恢复入口）。 */
    public Optional<HumanTicket> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        HumanTicket hit = tickets.get(id);
        if (hit != null) {
            return Optional.of(hit);
        }
        if (repository == null) {
            return Optional.empty();
        }
        try {
            Optional<HumanTicket> db = repository.findByTicketId(id).map(HumanTicketService::toDomain);
            db.ifPresent(t -> tickets.put(t.id(), t)); // 仅回源工单本体，不动键指针（防旧单抢占最新单语义）
            return db;
        } catch (Exception e) {
            log.warn("HITL 工单回源 DB 失败：ticket={} reason={}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** 按业务幂等键查最新工单（建单幂等/恢复漂移校验锚点；无键/未知键返回 empty）。 */
    public Optional<HumanTicket> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String id = idByKey.get(idempotencyKey);
        if (id != null) {
            return findById(id);
        }
        if (repository != null) {
            try {
                List<HitlTicketEntity> rows = repository.findByIdempotencyKeyOrderByCreatedAtDesc(idempotencyKey);
                if (!rows.isEmpty()) {
                    HumanTicket newest = toDomain(rows.get(0));
                    tickets.put(newest.id(), newest);
                    idByKey.put(idempotencyKey, newest.id()); // 按键查询回源：重建"指向最新单"语义
                    return Optional.of(newest);
                }
            } catch (Exception e) {
                log.warn("HITL 工单按键回源 DB 失败：key={} reason={}", idempotencyKey, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * 仅 PENDING 工单（管理台待审批列表）：内存 + DB 持久副本合并去重（重启后不丢单）；
     * DB 行回源只重建工单本体，不动键指针。
     */
    public List<HumanTicket> pendingTickets() {
        Map<String, HumanTicket> merged = new LinkedHashMap<>();
        for (HumanTicket t : tickets.values()) {
            if (t.status() == HumanTicket.Status.PENDING) {
                merged.put(t.id(), t);
            }
        }
        if (repository != null) {
            try {
                for (HitlTicketEntity e : repository.findByStatus(HumanTicket.Status.PENDING)) {
                    HumanTicket t = toDomain(e);
                    merged.putIfAbsent(t.id(), tickets.computeIfAbsent(t.id(), k -> t));
                }
            } catch (Exception e) {
                log.warn("HITL PENDING 工单列表回源 DB 失败（仅返回内存单）：reason={}", e.getMessage());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 全量工单（<b>含已决议</b>·管理台留痕视图·2026-09-20 提交制）：决议后工单不得从控制台消失——
     * 批准/驳回是事件，工单是唯一审计留痕，APPROVED/REJECTED/TIMEOUT 必须继续可见（带状态徽章）。
     * 内存 + DB 持久副本合并去重（重启后不丢历史）；排序 PENDING 优先，其余按 createdAt 倒序。
     */
    public List<HumanTicket> allTickets() {
        Map<String, HumanTicket> merged = new LinkedHashMap<>();
        for (HumanTicket t : tickets.values()) {
            merged.put(t.id(), t);
        }
        if (repository != null) {
            try {
                for (HitlTicketEntity e : repository.findAll()) {
                    HumanTicket t = toDomain(e);
                    merged.putIfAbsent(t.id(), tickets.computeIfAbsent(t.id(), k -> t));
                }
            } catch (Exception e) {
                log.warn("HITL 全量工单列表回源 DB 失败（仅返回内存单）：reason={}", e.getMessage());
            }
        }
        return merged.values().stream()
                .sorted(java.util.Comparator.<HumanTicket>comparingInt(t -> t.status() == HumanTicket.Status.PENDING ? 0 : 1)
                        .thenComparing(HumanTicket::createdAt, java.util.Comparator.reverseOrder()))
                .toList();
    }

    /** 全量工单（测试/管理台用；仅内存视图，无排序保证）。 */
    public List<HumanTicket> all() {
        return new ArrayList<>(tickets.values());
    }

    /**
     * 解析工单（APPROVED/REJECTED）——状态机守卫：仅 PENDING 单向转移。
     * 未知 id 幂等返回 {@link ResolveResult#CONFLICT}（不抛）。
     */
    public ResolveResult resolve(String id, HumanTicket.Status decision, Instant resolvedAt) {
        HumanTicket existing = (id == null) ? null : tickets.get(id);
        if (existing == null) {
            return ResolveResult.CONFLICT;
        }
        if (existing.status() == decision) {
            return ResolveResult.IDEMPOTENT_REPEAT; // 同决议重复提交：状态不变、无副作用
        }
        if (existing.status() != HumanTicket.Status.PENDING) {
            return ResolveResult.CONFLICT; // 反向决议/超时后决议：拒绝回翻
        }
        HumanTicket updated = new HumanTicket(
                existing.id(), existing.sessionId(), existing.query(), existing.reason(),
                decision, existing.createdAt(), resolvedAt, existing.idempotencyKey());
        tickets.put(id, updated);
        persistAsync(updated);
        return ResolveResult.TRANSITIONED;
    }

    /** 标记超时（仅 PENDING 可流转），未知/已决议 id 幂等不抛。 */
    public void markTimeout(String id, Instant resolvedAt) {
        resolve(id, HumanTicket.Status.TIMEOUT, resolvedAt);
    }

    /** 异步落库（单写线程保序：建单写必先于决议同步执行）；失败仅告警，内存状态不受影响。 */
    private void persistAsync(HumanTicket ticket) {
        if (repository == null) {
            return;
        }
        writer.execute(() -> {
            try {
                HitlTicketEntity existing = repository.findByTicketId(ticket.id()).orElse(null);
                if (existing == null) {
                    repository.save(HitlTicketEntity.from(ticket));
                } else {
                    existing.sync(ticket);
                    repository.save(existing);
                }
            } catch (Exception e) {
                log.warn("HITL 工单落库失败（内存状态仍有效）：ticket={} status={} reason={}", // 审计
                        ticket.id(), ticket.status(), e.getMessage());
            }
        });
    }

    private static HumanTicket toDomain(HitlTicketEntity e) {
        return new HumanTicket(e.getTicketId(), e.getSessionId(), e.getQuery(), e.getReason(),
                e.getStatus(), e.getCreatedAt(), e.getResolvedAt(), e.getIdempotencyKey());
    }
}
