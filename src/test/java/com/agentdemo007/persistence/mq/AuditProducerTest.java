package com.agentdemo007.persistence.mq;

import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 审计事件生产者测评。
 *
 * <p>{@link AuditProducer} 是 {@link MessagePublisher} seam 的领域封装——固定路由键 {@code audit.event}
 * + 透传 {@link AuditEvent} 载荷。终端后置钩子刷出 {@code context.auditEvents()} 经本生产者逐条投递；
 * 接入层注入等预流水线审计点也可直接调本生产者。单测验证路由键 + 载荷同引用透传。
 */
class AuditProducerTest {

    @Test
    void publish_delegatesWithAuditEventRoutingKeyAndSamePayload() {
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        AuditProducer producer = new AuditProducer(publisher);
        AuditEvent event = AuditEvent.of(AuditEventType.TOOL_CALL, "trace-2", "sess-2", "工具调用记录");

        producer.publish(event);

        assertThat(publisher.publishCount()).isEqualTo(1);
        assertThat(publisher.lastRoutingKey()).isEqualTo("audit.event");
        assertThat(publisher.lastRoutingKey()).isEqualTo(AuditProducer.ROUTING_KEY);
        assertThat(publisher.lastPayload()).isSameAs(event);
    }

    @Test
    void publishEach_flushesAllAuditEventsInOrder() {
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        AuditProducer producer = new AuditProducer(publisher);
        AuditEvent first = AuditEvent.of(AuditEventType.INJECTION, "t", "s", "注入命中");
        AuditEvent second = AuditEvent.of(AuditEventType.HITL, "t", "s", "转人工");

        producer.publishEach(java.util.List.of(first, second));

        assertThat(publisher.publishCount()).isEqualTo(2);
        assertThat(publisher.published().get(0).getValue()).isSameAs(first);
        assertThat(publisher.published().get(1).getValue()).isSameAs(second);
        assertThat(publisher.published()).allMatch(e -> "audit.event".equals(e.getKey()));
    }
}
