package com.agentdemo007.persistence.mq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 13 会话持久化 MQ 发 seam 的 dev 兜底实现测评。
 *
 * <p>生产者（{@code HistoryPersistProducer}/>{@code AuditProducer}）依赖 {@link MessagePublisher} 接口缝而非
 * {@code RabbitTemplate}（§5.14 引擎无关 seam）。dev 默认装配 {@link NoopMessagePublisher}——仅记日志、不连 broker、
 * 不抛异常（§5.12 能跑通&gt;完美：停 MQ 主接口照常 200）。单测无需 Spring 容器，直接 new 验证行为。
 */
class NoopMessagePublisherTest {

    @Test
    void publish_doesNotThrow_forAnyRoutingKeyAndPayload() {
        MessagePublisher publisher = new NoopMessagePublisher();

        assertThatCode(() -> publisher.publish("session.persist", new ChatTurnEvent(
                "trace-1", "sess-1", "你好", "您好",
                null, false, null, java.time.OffsetDateTime.now())))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_swallowsNullPayloadWithoutThrowing() {
        MessagePublisher publisher = new NoopMessagePublisher();

        assertThatCode(() -> publisher.publish("audit.event", null))
                .doesNotThrowAnyException();
    }

    @Test
    void noop_isAssignableToMessagePublisherSeam() {
        assertThat(new NoopMessagePublisher()).isInstanceOf(MessagePublisher.class);
    }
}
