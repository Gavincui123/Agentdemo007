package com.agentdemo007.persistence.repository;

import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import com.agentdemo007.persistence.entity.AuditEventEntity;
import com.agentdemo007.persistence.entity.ChatTurnEntity;
import com.agentdemo007.persistence.mq.ChatTurnEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 会话/审计持久化实体与 Repository 测评。
 *
 * <p>MQ 消费者（Phase 13 T55）落库时将强类型 MQ 载荷（{@link ChatTurnEvent}/{@link AuditEvent}）
 * 映射为 JPA 实体（§5.14：禁止 Map，强类型 record→实体字段对齐）。本片验证 save→findById 往返。
 *
 * <p>Boot 4.1.1 已移除 {@code @DataJpaTest}/{@code @AutoConfigureTestDatabase} 切片（jar 内无对应类），
 * 故改用 {@code @SpringBootTest}（与 {@code contextLoads} 共享缓存上下文，启动快）+ {@code @Transactional}
 * 实现每测试回滚隔离。H2 内存库 + ddl-auto=update 自动建表。
 */
@SpringBootTest
@Transactional
class PersistenceRepositoryTest {

    @Autowired
    private ChatTurnRepository chatTurnRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void chatTurnRepository_savesAndRetrievesFromEvent() {
        ChatTurnEvent event = new ChatTurnEvent(
                "trace-1", "sess-1", "Q3 销售额多少", "Q3 销售额为 1.2 亿元。",
                "REASONING", false, null, OffsetDateTime.now());

        ChatTurnEntity saved = chatTurnRepository.save(ChatTurnEntity.from(event));

        assertThat(saved.getId()).isNotNull();
        Optional<ChatTurnEntity> found = chatTurnRepository.findById(saved.getId());
        assertThat(found).isPresent();
        ChatTurnEntity e = found.get();
        assertThat(e.getTraceId()).isEqualTo("trace-1");
        assertThat(e.getSessionId()).isEqualTo("sess-1");
        assertThat(e.getRawInput()).isEqualTo("Q3 销售额多少");
        assertThat(e.getFinalReply()).isEqualTo("Q3 销售额为 1.2 亿元。");
        assertThat(e.getIntent()).isEqualTo("REASONING");
        assertThat(e.isDegraded()).isFalse();
        assertThat(e.getScenario()).isNull();
    }

    @Test
    void chatTurnRepository_persistsDegradedScenario() {
        ChatTurnEvent event = new ChatTurnEvent(
                "trace-2", "sess-2", "你好", "我暂时无法回应，请稍后重试。",
                null, true, "MODEL_DOWN", OffsetDateTime.now());

        ChatTurnEntity saved = chatTurnRepository.save(ChatTurnEntity.from(event));

        assertThat(saved.isDegraded()).isTrue();
        assertThat(saved.getScenario()).isEqualTo("MODEL_DOWN");
        assertThat(saved.getIntent()).isNull();
    }

    @Test
    void auditEventRepository_savesAndRetrievesFromAuditEvent() {
        AuditEvent event = AuditEvent.of(AuditEventType.INJECTION, "trace-3", "sess-3", "注入命中：drop table");

        AuditEventEntity saved = auditEventRepository.save(AuditEventEntity.from(event));

        assertThat(saved.getId()).isNotNull();
        AuditEventEntity e = auditEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(e.getType()).isEqualTo(AuditEventType.INJECTION);
        assertThat(e.getTraceId()).isEqualTo("trace-3");
        assertThat(e.getSessionId()).isEqualTo("sess-3");
        assertThat(e.getDetail()).isEqualTo("注入命中：drop table");
    }

    // ---- Phase 13 T58：JPA 审计 @CreatedDate 落库时间戳（§5.11 不可篡改留存）----

    @Test
    void chatTurnEntity_persistsCreatedAtViaJpaAuditing() {
        ChatTurnEntity saved = chatTurnRepository.save(ChatTurnEntity.from(
                new ChatTurnEvent("trace-a", "sess-a", "你好", "您好", null, false, null, OffsetDateTime.now())));

        assertThat(saved.getCreatedAt()).isNotNull(); // @CreatedDate 落库时间戳自动填充
    }

    @Test
    void auditEventEntity_persistsCreatedAtViaJpaAuditing() {
        AuditEventEntity saved = auditEventRepository.save(AuditEventEntity.from(
                AuditEvent.of(AuditEventType.INJECTION, "trace-b", null, "注入命中:test")));

        assertThat(saved.getCreatedAt()).isNotNull(); // 审计行同样带落库时间戳
    }

    // ---- Phase 19 T98：会话历史派生查询（按 sessionId 时间升序）----

    @Test
    void chatTurnRepository_findsBySessionIdOrdered() {
        chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "t1", "sess-ord", "一", "答一", null, false, null,
                OffsetDateTime.parse("2026-09-08T09:00:00Z"))));
        chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "t2", "sess-ord", "二", "答二", null, false, null,
                OffsetDateTime.parse("2026-09-08T09:05:00Z"))));
        chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "t3", "sess-other", "三", "答三", null, false, null,
                OffsetDateTime.parse("2026-09-08T09:00:00Z"))));

        var turns = chatTurnRepository.findBySessionIdOrderByTimestampAsc("sess-ord");

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).getRawInput()).isEqualTo("一");
        assertThat(turns.get(1).getRawInput()).isEqualTo("二"); // 时间升序
    }

    // ---- Phase 19 T99：降级轮次计数派生查询（可观测快照消费）----

    @Test
    void chatTurnRepository_countsDegradedTrue() {
        chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "d1", "sess-d", "正常", "正常答", null, false, null,
                OffsetDateTime.parse("2026-09-08T09:00:00Z"))));
        chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "d2", "sess-d", "降级", "降级答", null, true, "MODEL_DOWN",
                OffsetDateTime.parse("2026-09-08T09:05:00Z"))));
        chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "d3", "sess-d", "再降级", "降级答2", null, true, "RAG_SKIP",
                OffsetDateTime.parse("2026-09-08T09:10:00Z"))));

        assertThat(chatTurnRepository.countByDegradedTrue()).isEqualTo(2L); // 仅降级行
        assertThat(chatTurnRepository.count()).isEqualTo(3L);
    }
}
