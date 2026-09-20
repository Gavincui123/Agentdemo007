package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.persistence.entity.HitlCheckpointEntity;
import com.agentdemo007.persistence.repository.HitlCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HITL 挂起检查点服务（L2 挂起-恢复·2026-09-18；Redis+DB 双持久副本·同日用户裁决）。
 *
 * <p><b>三副本分层</b>（用户裁决：checkpoint 必须 DB 持久化，Redis 与 DB 各存一份，防重启丢失；
 * 所有持久化写入均异步，不阻塞主线）：
 * <ol>
 *   <li><b>内存</b>（主读路径，§5.12 降级：DB/Redis 抖动不阻塞挂起/恢复，仅告警）；</li>
 *   <li><b>Redis 热副本</b>（{@code hitl:checkpoint:{ticketId}} → 快照 JSON，TTL
 *       {@code app.hitl.checkpoint-redis-ttl} 默认 24h；随 {@code app.redis.enabled} 生效）——
 *       读路径第二层；消费/作废时异步删除，删除失败由冷路径 <b>DB 对账</b> 兜住
 *       （Redis 陈旧 ACTIVE 不得复活已消费/已作废的检查点）；</li>
 *   <li><b>DB 持久副本</b>（{@code hitl_checkpoint} 表，重启后权威回源）——读路径末层。</li>
 * </ol>
 * 写路径：HitlStep(610) 挂起时同步序列化快照 + 内存即时可读，单守护线程
 * {@code hitl-checkpoint-writer} 异步写 Redis + DB（双写 best-effort，任一失败仅告警）。
 *
 * <p><b>严格锚点匹配</b>（用户裁决：检查点幂等键 != 工单幂等键 → 不可用）：恢复/补挂一律经
 * {@link #findActiveForTicket}——键不一致即作废留痕，<b>不产出检查点</b>；允许多工单并存
 * （TIMEOUT 重建等），但每个工单只能消费与自身键完全一致的检查点。
 *
 * <p>状态机（per checkpoint）：ACTIVE → CONSUMED（{@link #consume} CAS，防重复恢复提交）/
 * EXPIRED（漂移校验失败作废）。CAS 以内存为源（单实例 demo 部署；多实例需 DB 乐观锁，迭代后补）。
 */
public class HitlCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(HitlCheckpointService.class);

    /** Redis 键前缀：{@code hitl:checkpoint:{ticketId}} → 快照 JSON。 */
    public static final String REDIS_KEY_PREFIX = "hitl:checkpoint:";
    private static final Duration DEFAULT_REDIS_TTL = Duration.ofHours(24);

    /** 内存条目：快照 + 状态机（compute 内迁移，per-key 原子）。 */
    private static final class Entry {
        final HitlCheckpointSnapshot snapshot;
        final AtomicReference<HitlCheckpointEntity.Status> status = new AtomicReference<>(HitlCheckpointEntity.Status.ACTIVE);
        volatile String resumeReply;
        volatile String expireReason;

        Entry(HitlCheckpointSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private final ConcurrentHashMap<String, Entry> byTicket = new ConcurrentHashMap<>();
    private final HitlCheckpointRepository repository; // 可空：无 JPA 环境（部分单测）内存降级
    private final ObjectMapper objectMapper;
    private final Executor writer;
    private final StringRedisTemplate redisTemplate; // 可空：未启用 Redis 时为 null（内存+DB 双副本）
    private final Duration redisTtl;

    public HitlCheckpointService(HitlCheckpointRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, null, null, null);
    }

    public HitlCheckpointService(HitlCheckpointRepository repository, ObjectMapper objectMapper, Executor writer) {
        this(repository, objectMapper, writer, null, null);
    }

    /** 全参构造（Redis 热副本可选）：{@code redisTemplate} 为 null 时退化为内存+DB 双副本。 */
    public HitlCheckpointService(HitlCheckpointRepository repository, ObjectMapper objectMapper, Executor writer,
                                 StringRedisTemplate redisTemplate, Duration redisTtl) {
        this.repository = repository;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.writer = (writer != null) ? writer : defaultWriter();
        this.redisTemplate = redisTemplate;
        this.redisTtl = (redisTtl != null && !redisTtl.isZero() && !redisTtl.isNegative()) ? redisTtl : DEFAULT_REDIS_TTL;
    }

    private static Executor defaultWriter() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hitl-checkpoint-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 挂起即保存：从挂起上下文构建最小快照（routePlan JSON 同步序列化，失败如实降级为 null
     * ——恢复执行时路由门控按 null 兜底，不阻塞挂起）并异步落 Redis + DB。
     */
    public void saveFor(PipelineContext context, HumanTicket ticket, String idempotencyKey) {
        HitlCheckpointSnapshot snapshot = new HitlCheckpointSnapshot(
                ticket.id(),
                idempotencyKey,
                context.traceId(),
                context.sessionId(),
                context.userId(),
                context.rawInput(),
                (context.standardQuery() != null) ? context.standardQuery().text() : null,
                (context.intent() != null) ? context.intent().name() : null,
                routePlanJson(context.routePlan()),
                System.currentTimeMillis());
        saveAsync(snapshot);
    }

    /**
     * 异步双写（内存即时可读；序列化已在调用线程完成，本方法只做 I/O 异步化）。
     * 无业务键仅内存（不伪造恢复锚点）；序列化失败 DB/Redis 副本缺该条，内存仍可恢复（如实告警）。
     */
    public void saveAsync(HitlCheckpointSnapshot snapshot) {
        String json = toJson(snapshot);
        byTicket.put(snapshot.ticketId(), new Entry(snapshot));
        if (json == null
                || (repository == null && redisTemplate == null)
                || snapshot.idempotencyKey() == null || snapshot.idempotencyKey().isBlank()) {
            return;
        }
        writer.execute(() -> {
            writeRedis(snapshot.ticketId(), json);
            upsertDb(snapshot, json);
        });
    }

    /** 查 ACTIVE 检查点（键不敏感，兼容口径）：内存优先，未命中回源 Redis → DB 重建（重启恢复路径）。 */
    public Optional<HitlCheckpointSnapshot> findActive(String ticketId) {
        Entry e = byTicket.get(ticketId);
        if (e == null) {
            e = rehydrate(ticketId);
        }
        return (e != null && e.status.get() == HitlCheckpointEntity.Status.ACTIVE)
                ? Optional.of(e.snapshot) : Optional.empty();
    }

    /**
     * 查 ACTIVE 检查点（<b>严格锚点匹配</b>·用户裁决）：检查点幂等键 != 工单幂等键 →
     * 视为漂移，作废留痕并返回 empty（<b>不产出检查点</b>，下游不得恢复/补挂消费它）。
     * 允许多工单并存：每个工单只可用与自身键完全一致的检查点。
     */
    public Optional<HitlCheckpointSnapshot> findActiveForTicket(String ticketId, String expectedKey) {
        Optional<HitlCheckpointSnapshot> found = findActive(ticketId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (expectedKey == null || expectedKey.isBlank() || !expectedKey.equals(found.get().idempotencyKey())) {
            log.warn("HITL 检查点锚点不一致，作废不产出：ticket={} expected={} actual={}", // 审计
                    ticketId, expectedKey, found.get().idempotencyKey());
            expire(ticketId, "漂移：检查点幂等键与工单不一致（严格锚点匹配）");
            return Optional.empty();
        }
        return found;
    }

    /**
     * 冷路径回源：Redis 热副本（命中后与 DB 对账，DB 为状态真相源——Redis 陈旧 ACTIVE 不得
     * 复活已消费/已作废检查点）→ DB 持久副本。命中即重建内存条目（后续读走内存）。
     */
    private Entry rehydrate(String ticketId) {
        Entry e = byTicket.get(ticketId); // 并发双检：他线程已回源则直接用
        if (e != null) {
            return e;
        }
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + ticketId);
                if (json != null && !json.isBlank()) {
                    if (dbStatusIsNotActive(ticketId)) {
                        evictRedis(ticketId); // Redis 陈旧：以 DB 为准并剔除热副本
                        return null;
                    }
                    HitlCheckpointSnapshot snap = objectMapper.readValue(json, HitlCheckpointSnapshot.class);
                    e = new Entry(snap);
                    byTicket.put(ticketId, e);
                    return e;
                }
            } catch (Exception ex) {
                log.warn("HITL checkpoint 回源 Redis 失败（转 DB 回源）：ticket={} reason={}",
                        ticketId, ex.getMessage());
            }
        }
        if (repository != null) {
            try {
                Optional<HitlCheckpointEntity> db = repository.findByTicketId(ticketId)
                        .filter(en -> en.getStatus() == HitlCheckpointEntity.Status.ACTIVE);
                if (db.isPresent() && db.get().getSnapshotJson() != null) {
                    HitlCheckpointSnapshot snap = objectMapper.readValue(db.get().getSnapshotJson(),
                            HitlCheckpointSnapshot.class);
                    e = new Entry(snap);
                    byTicket.put(ticketId, e);
                }
            } catch (Exception ex) {
                log.warn("HITL checkpoint 回源 DB 失败：ticket={} reason={}", ticketId, ex.getMessage());
            }
        }
        return e;
    }

    /** DB 状态对账（仅 Redis 冷路径）：DB 可达且状态非 ACTIVE → true（Redis 副本视为陈旧）。 */
    private boolean dbStatusIsNotActive(String ticketId) {
        if (repository == null) {
            return false;
        }
        try {
            return repository.findByTicketId(ticketId)
                    .map(en -> en.getStatus() != HitlCheckpointEntity.Status.ACTIVE)
                    .orElse(false); // DB 无行（Redis 有）→ 信任 Redis 副本（DB 抖动/落库失败场景）
        } catch (Exception e) {
            log.warn("HITL checkpoint Redis 副本 DB 对账失败（按 Redis 继续）：ticket={} reason={}",
                    ticketId, e.getMessage());
            return false;
        }
    }

    /**
     * 消费检查点（恢复前 CAS）：ACTIVE → CONSUMED，仅首个调用者返回 true——
     * 重复恢复提交（双击审批/并发 resume）在此被挡，不重复执行后段流水线。
     */
    public boolean consume(String ticketId) {
        boolean[] ok = {false};
        byTicket.computeIfPresent(ticketId, (k, e) -> {
            if (e.status.compareAndSet(HitlCheckpointEntity.Status.ACTIVE, HitlCheckpointEntity.Status.CONSUMED)) {
                ok[0] = true;
            }
            return e;
        });
        if (ok[0] && (repository != null || redisTemplate != null)) {
            writer.execute(() -> {
                evictRedis(ticketId); // 热副本同步移除（删除失败由冷路径 DB 对账兜住）
                syncStatus(ticketId, en -> en.setStatus(HitlCheckpointEntity.Status.CONSUMED));
            });
        }
        return ok[0];
    }

    /** 漂移校验失败作废：ACTIVE/CONSUMED → EXPIRED（留原因，审计可溯）。 */
    public void expire(String ticketId, String reason) {
        byTicket.computeIfPresent(ticketId, (k, e) -> {
            e.expireReason = reason;
            e.status.set(HitlCheckpointEntity.Status.EXPIRED);
            return e;
        });
        if (repository != null || redisTemplate != null) {
            writer.execute(() -> {
                evictRedis(ticketId);
                if (repository != null) {
                    syncStatus(ticketId, en -> en.markExpired(reason));
                }
            });
        }
    }

    /** 恢复完成后回填终态留痕（回复 + 时刻）。 */
    public void markResumed(String ticketId, String reply, Instant resumedAt) {
        Entry e = byTicket.get(ticketId);
        if (e != null) {
            e.resumeReply = reply;
        }
        if (repository != null) {
            writer.execute(() -> syncStatus(ticketId, en -> en.markResumed(resumedAt, reply)));
        }
    }

    private void writeRedis(String ticketId, String json) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + ticketId, json, redisTtl);
        } catch (Exception e) {
            log.warn("HITL checkpoint 写 Redis 副本失败（DB 副本仍写，不影响挂起）：ticket={} reason={}",
                    ticketId, e.getMessage()); // 审计
        }
    }

    private void evictRedis(String ticketId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + ticketId);
        } catch (Exception e) {
            log.warn("HITL checkpoint 删除 Redis 副本失败（冷路径 DB 对账兜底）：ticket={} reason={}",
                    ticketId, e.getMessage());
        }
    }

    private void upsertDb(HitlCheckpointSnapshot snapshot, String json) {
        if (repository == null) {
            return;
        }
        try {
            HitlCheckpointEntity existing = repository.findByTicketId(snapshot.ticketId()).orElse(null);
            if (existing == null) {
                repository.save(HitlCheckpointEntity.from(snapshot, json));
            } else {
                existing.setStatus(HitlCheckpointEntity.Status.ACTIVE);
                repository.save(existing); // TIMEOUT 重建后再次挂起：复用行重置 ACTIVE
            }
        } catch (Exception e) {
            log.warn("HITL checkpoint 落库失败（内存/Redis 副本仍可恢复，不影响挂起）：ticket={} reason={}",
                    snapshot.ticketId(), e.getMessage()); // 审计
        }
    }

    private interface StatusMutator {
        void apply(HitlCheckpointEntity en);
    }

    private void syncStatus(String ticketId, StatusMutator mutator) {
        if (repository == null) {
            return;
        }
        try {
            repository.findByTicketId(ticketId).ifPresent(en -> {
                mutator.apply(en);
                repository.save(en);
            });
        } catch (Exception e) {
            log.warn("HITL checkpoint 状态同步 DB 失败（内存已生效）：ticket={} reason={}",
                    ticketId, e.getMessage()); // 审计
        }
    }

    private String routePlanJson(RoutePlan routePlan) {
        if (routePlan == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(routePlan);
        } catch (Exception e) {
            log.warn("RoutePlan 快照序列化失败（恢复执行按 null 规划兜底）：{}", e.getMessage());
            return null;
        }
    }

    private String toJson(HitlCheckpointSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("HITL checkpoint 快照序列化失败（DB/Redis 副本缺该条，内存仍可恢复）：ticket={} reason={}",
                    snapshot.ticketId(), e.getMessage());
            return null;
        }
    }
}
