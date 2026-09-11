package com.agentdemo007.persistence;

import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import com.agentdemo007.persistence.entity.AuditEventEntity;
import com.agentdemo007.persistence.entity.ChatTurnEntity;
import com.agentdemo007.persistence.mq.ChatTurnEvent;
import com.agentdemo007.persistence.repository.AuditEventRepository;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code schema.sql} 建表路径回归（prod 部署基础设施）。
 *
 * <p>prod profile 固定 {@code ddl-auto=none} + {@code spring.sql.init.mode=always}，由
 * {@code classpath:schema.sql} 建 {@code chat_turn}/{@code audit_event}（项目未集成 Flyway/Liquibase）。
 * 本测试强制该路径：覆盖 {@code ddl-auto=none} + {@code sql.init.mode=always}，在 dev 的
 * H2(MODE=MySQL) 上执行 {@code schema.sql}，随后 save→find 往返验证字段对齐。
 *
 * <p>闭环逻辑：{@code ddl-auto=none} 下 Hibernate 不建表；若 {@code schema.sql} 不执行、
 * 或列名/类型与 {@code @Column} 漂移、或语法被 H2/MySQL 拒绝 → save 抛异常（红）。
 * 任何一次回滚对 {@code schema.sql} 的破坏都将在此暴露。
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always"
})
@Transactional
class SchemaSqlInitializationTest {

    @Autowired
    private ChatTurnRepository chatTurnRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void chatTurn_saveAndFindUnderSchemaSqlPath() {
        ChatTurnEntity saved = chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "sch-trace", "sch-sess", "Q3 销售额多少", "Q3 销售额为 1.2 亿元。",
                "REASONING", false, null, OffsetDateTime.now())));

        assertThat(saved.getId()).isNotNull();
        ChatTurnEntity e = chatTurnRepository.findById(saved.getId()).orElseThrow();
        assertThat(e.getTraceId()).isEqualTo("sch-trace");
        assertThat(e.getSessionId()).isEqualTo("sch-sess");
        assertThat(e.getRawInput()).isEqualTo("Q3 销售额多少");
        assertThat(e.getFinalReply()).isEqualTo("Q3 销售额为 1.2 亿元。");
        assertThat(e.getIntent()).isEqualTo("REASONING");
        assertThat(e.isDegraded()).isFalse();
        assertThat(e.getScenario()).isNull();
    }

    @Test
    void chatTurn_persistsDegradedAndTextFieldsUnderSchemaSqlPath() {
        String longInput = "x".repeat(5000); // TEXT 列承载长文本（VARCHAR(64) 量级校验对照）
        ChatTurnEntity saved = chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "sch-trace-2", "sch-sess-2", longInput, "降级回复。",
                null, true, "MODEL_DOWN", OffsetDateTime.now())));

        ChatTurnEntity e = chatTurnRepository.findById(saved.getId()).orElseThrow();
        assertThat(e.isDegraded()).isTrue();        // BIT(1) 列与 boolean 映射
        assertThat(e.getScenario()).isEqualTo("MODEL_DOWN");
        assertThat(e.getRawInput()).hasSize(5000);  // TEXT 列承载完整长文本
    }

    @Test
    void auditEvent_saveAndFindUnderSchemaSqlPath() {
        AuditEventEntity saved = auditEventRepository.save(AuditEventEntity.from(
                AuditEvent.of(AuditEventType.INJECTION, "sch-aud", "sch-aud-sess", "注入命中：drop table")));

        assertThat(saved.getId()).isNotNull();
        AuditEventEntity e = auditEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(e.getType()).isEqualTo(AuditEventType.INJECTION); // VARCHAR(32) 存枚举名
        assertThat(e.getTraceId()).isEqualTo("sch-aud");
        assertThat(e.getSessionId()).isEqualTo("sch-aud-sess");
        assertThat(e.getDetail()).isEqualTo("注入命中：drop table"); // TEXT 列
    }

    @Test
    void jpaAuditing_createdDateFilledUnderSchemaSqlPath() {
        ChatTurnEntity saved = chatTurnRepository.save(ChatTurnEntity.from(new ChatTurnEvent(
                "sch-aud-trace", "sch-aud-sess", "你好", "您好", null, false, null, OffsetDateTime.now())));

        // @CreatedDate 在 schema.sql 建表路径下仍自动填充（created_at TIMESTAMP(6) NOT NULL）
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
