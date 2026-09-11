package com.agentdemo007.persistence.mq;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 会话持久化生产者测评。
 *
 * <p>{@link HistoryPersistProducer} 是 {@link MessagePublisher} seam 的领域封装——固定路由键
 * {@code session.persist} + 透传 {@link ChatTurnEvent} 载荷。终端后置钩子 {@code ChatTurnFinalizer}
 * 组装事件后调本生产者投递。单测用 {@link CapturingMessagePublisher} 假替换 seam，验证路由键 + 载荷同引用透传。
 */
class HistoryPersistProducerTest {

    @Test
    void publish_delegatesWithSessionPersistRoutingKeyAndSamePayload() {
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        HistoryPersistProducer producer = new HistoryPersistProducer(publisher);
        ChatTurnEvent event = new ChatTurnEvent(
                "trace-1", "sess-1", "你好", "您好",
                "REASONING", false, null, OffsetDateTime.now());

        producer.publish(event);

        assertThat(publisher.publishCount()).isEqualTo(1);
        assertThat(publisher.lastRoutingKey()).isEqualTo("session.persist");
        assertThat(publisher.lastRoutingKey()).isEqualTo(HistoryPersistProducer.ROUTING_KEY);
        assertThat(publisher.lastPayload()).isSameAs(event);
    }
}
