package com.agentdemo007.capability.hitl;

import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.persistence.entity.HitlCheckpointEntity;
import com.agentdemo007.persistence.repository.HitlCheckpointRepository;
import com.agentdemo007.session.model.StandardQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITL 挂起检查点服务测试（2026-09-18 L2；Redis+DB 双副本 + 严格锚点匹配同日扩展）。
 *
 * <p>覆盖：挂起即保存（内存即时可读 + 异步落库直跑）/ 消费 CAS（仅首个成功，防重复恢复提交）/
 * 作废留痕 / DB 回源重建（重启恢复路径）/ DB 故障降级（内存仍可恢复，§5.12）/
 * Redis 热副本（异步双写、内存未命中回源重建、陈旧副本 DB 对账兜底）/
 * <b>严格锚点匹配</b>（检查点幂等键 != 工单幂等键 → 作废不产出，多工单场景各消费自身键）。
 * repo 用 Mockito 桩，writer 用直跑执行器（同步可断言）。
 */
class HitlCheckpointServiceTest {

    private static HitlCheckpointSnapshot snapshot(String ticketId, String key) {
        return new HitlCheckpointSnapshot(ticketId, key, "trace-1", "sess-1", "10086",
                "我要退款 ORD-001", "退款 ORD-001", "TRANSFER_TO_HUMAN", null, 1L);
    }

    @Test
    void saveFor_memoryImmediatelyReadable_snapshotCarriesContext() {
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
        PipelineContext ctx = new PipelineContext("sess-1", "我要退款 ORD-001");
        ctx.setUserId("10086");
        ctx.setStandardQuery(StandardQuery.of("退款 ORD-001"));
        ctx.setIntent(Intent.TRANSFER_TO_HUMAN);
        RoutePlanCandidate candidate = new RoutePlanCandidate("refund", false, false,
                java.util.List.of(), java.util.List.of(), RiskLevel.HIGH, false, FallbackPolicy.WORKFLOW_FIRST);
        ctx.setRoutePlan(new RoutePlan(candidate, RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS, 0.9, java.util.List.of()));
        HumanTicket ticket = new HumanTicket("t-1", "sess-1", "我要退款 ORD-001", "r",
                HumanTicket.Status.PENDING, Instant.now(), null);

        service.saveFor(ctx, ticket, "hitl:refund:ORD-001");

        Optional<HitlCheckpointSnapshot> found = service.findActive("t-1");
        assertThat(found).isPresent();
        HitlCheckpointSnapshot snap = found.orElseThrow();
        assertThat(snap.idempotencyKey()).isEqualTo("hitl:refund:ORD-001");
        assertThat(snap.sessionId()).isEqualTo("sess-1");
        assertThat(snap.userId()).isEqualTo("10086");
        assertThat(snap.standardQueryText()).isEqualTo("退款 ORD-001");
        assertThat(snap.intentName()).isEqualTo("TRANSFER_TO_HUMAN");
        assertThat(snap.routePlanJson()).contains("refund"); // routePlan JSON 快照（恢复执行门控依赖）
    }

    @Test
    void consume_casOnlyFirstCallerWins() {
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
        service.saveAsync(snapshot("t-1", "hitl:refund:ORD-001"));

        assertThat(service.consume("t-1")).isTrue();  // 首个恢复提交
        assertThat(service.consume("t-1")).isFalse(); // 重复提交被拒
        assertThat(service.findActive("t-1")).isEmpty(); // CONSUMED 不再是 ACTIVE
    }

    @Test
    void expire_marksInactive() {
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
        service.saveAsync(snapshot("t-1", "hitl:refund:ORD-001"));

        service.expire("t-1", "漂移：幂等键已指向更新工单");

        assertThat(service.findActive("t-1")).isEmpty();
    }

    @Test
    void consume_unknownTicket_false() {
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
        assertThat(service.consume("no-such")).isFalse();
    }

    @Test
    void dbWriteFailure_degradesToMemory_warnNotThrow() {
        // §5.12 降级：DB 抖动不阻塞挂起/恢复，内存仍可读
        HitlCheckpointRepository repo = mock(HitlCheckpointRepository.class);
        when(repo.findByTicketId(any())).thenReturn(Optional.empty());
        when(repo.save(any(HitlCheckpointEntity.class))).thenThrow(new RuntimeException("db down"));
        HitlCheckpointService service = new HitlCheckpointService(repo, new ObjectMapper(), Runnable::run);

        service.saveAsync(snapshot("t-1", "hitl:refund:ORD-001")); // 落库失败仅告警

        assertThat(service.findActive("t-1")).isPresent(); // 内存仍可恢复
    }

    @Test
    void memoryMiss_rehydratesFromDb() {
        // 重启恢复路径：内存空 → 回源 DB（ACTIVE 快照 JSON）重建
        HitlCheckpointRepository repo = mock(HitlCheckpointRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        String json;
        try {
            json = mapper.writeValueAsString(snapshot("t-1", "hitl:refund:ORD-001"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        HitlCheckpointEntity entity = HitlCheckpointEntity.from(snapshot("t-1", "hitl:refund:ORD-001"), json);
        when(repo.findByTicketId("t-1")).thenReturn(Optional.of(entity));
        HitlCheckpointService service = new HitlCheckpointService(repo, mapper, Runnable::run);

        Optional<HitlCheckpointSnapshot> found = service.findActive("t-1");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().idempotencyKey()).isEqualTo("hitl:refund:ORD-001");
    }

    // ---- 2026-09-18 同日扩展：严格锚点匹配 + Redis 热副本 ----

    @Test
    void findActiveForTicket_keyMismatch_expiresAndReturnsEmpty() {
        // 用户裁决：检查点幂等键 != 工单幂等键 → 不可查/不可用（作废留痕），多工单各匹配自身键
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
        service.saveAsync(snapshot("t-1", "hitl:REFUND:ORD-001"));

        assertThat(service.findActiveForTicket("t-1", "hitl:REFUND:ORD-002")).isEmpty();
        assertThat(service.findActive("t-1")).isEmpty(); // 已作废（EXPIRED），不产出
    }

    @Test
    void findActiveForTicket_nullKey_ticketWithoutAnchor_getsNoCheckpoint() {
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run);
        service.saveAsync(snapshot("t-1", "hitl:REFUND:ORD-001"));

        assertThat(service.findActiveForTicket("t-1", null)).isEmpty();
    }

    @Test
    void redisCopy_writtenOnSave_andRehydratedOnMemoryMiss() {
        // Redis 热副本：写路径异步落 Redis；新实例（内存空）读路径回源 Redis 重建
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectMapper mapper = new ObjectMapper();
        HitlCheckpointService writer = new HitlCheckpointService(null, mapper, Runnable::run, redis, Duration.ofHours(24));

        writer.saveAsync(snapshot("t-1", "hitl:refund:ORD-001"));

        org.mockito.ArgumentCaptor<String> jsonCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ops).set(org.mockito.ArgumentMatchers.eq("hitl:checkpoint:t-1"), jsonCap.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)));
        assertThat(jsonCap.getValue()).contains("hitl:refund:ORD-001");

        // 重启语义：新实例内存空 → Redis 命中回源
        HitlCheckpointService restarted = new HitlCheckpointService(null, mapper, Runnable::run, redis, Duration.ofHours(24));
        when(ops.get("hitl:checkpoint:t-1")).thenReturn(jsonCap.getValue());
        assertThat(restarted.findActive("t-1")).isPresent();
        assertThat(restarted.findActive("t-1").orElseThrow().idempotencyKey()).isEqualTo("hitl:refund:ORD-001");
    }

    @Test
    void redisStaleActive_dbConsumedTruthWins_noResurrection() {
        // Redis 消费后删除失败遗留陈旧 ACTIVE 副本：冷路径与 DB 对账，DB=CONSUMED 为准（不得复活）
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        ObjectMapper mapper = new ObjectMapper();
        String json;
        try {
            json = mapper.writeValueAsString(snapshot("t-1", "hitl:refund:ORD-001"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(ops.get("hitl:checkpoint:t-1")).thenReturn(json);
        HitlCheckpointRepository repo = mock(HitlCheckpointRepository.class);
        HitlCheckpointEntity consumed = HitlCheckpointEntity.from(snapshot("t-1", "hitl:refund:ORD-001"), json);
        consumed.setStatus(HitlCheckpointEntity.Status.CONSUMED);
        when(repo.findByTicketId("t-1")).thenReturn(Optional.of(consumed));
        HitlCheckpointService service = new HitlCheckpointService(repo, mapper, Runnable::run, redis, Duration.ofHours(24));

        assertThat(service.findActive("t-1")).isEmpty();
        verify(redis).delete("hitl:checkpoint:t-1"); // 陈旧热副本被剔除
    }

    @Test
    void consume_removesRedisCopy() {
        // 消费检查点 → Redis 热副本同步移除（防止重启后陈旧副本复活）
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        HitlCheckpointService service = new HitlCheckpointService(null, new ObjectMapper(), Runnable::run,
                redis, Duration.ofHours(24));
        service.saveAsync(snapshot("t-1", "hitl:refund:ORD-001"));

        assertThat(service.consume("t-1")).isTrue();
        verify(redis).delete("hitl:checkpoint:t-1");
    }
}
