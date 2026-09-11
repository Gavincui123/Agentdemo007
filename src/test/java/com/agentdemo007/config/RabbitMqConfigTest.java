package com.agentdemo007.config;

import com.agentdemo007.persistence.mq.MessagePublisher;
import com.agentdemo007.persistence.mq.RabbitMqMessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 RabbitMQ 生产装配 wiring 测评。
 *
 * <p>{@code app.rabbitmq.enabled=true} 时 {@link RabbitMqConfig} 条件装配——真实
 * {@link RabbitMqMessagePublisher} 覆盖 dev {@code NoopMessagePublisher}（后者 guard 为 {@code enabled=false}），
 * Jackson 3 转换器 + exchange/queue/binding 元数据 bean 就绪。停 broker 不影响启动：Queue/Exchange 为元数据，
 * {@code RabbitAdmin} 懒声明（首次连接才声明）；{@code @RabbitListener} 消费者在此装配下也会加载，但
 * {@code spring.rabbitmq.listener.simple.auto-startup=false} 抑制其容器启动→不在配置测评里连 broker
 * （消费者 ack/nack 语义由单测 {@code HistoryPersistConsumerTest}/{@code AuditConsumerTest} 验证）。
 *
 * <p>RED 前提：无 {@link RabbitMqConfig} 时 {@code enabled=true} → Noop 不装配 → 无 {@link MessagePublisher} bean。
 */
@SpringBootTest(properties = {
        "app.rabbitmq.enabled=true",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class RabbitMqConfigTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void realPublisherBeanWired_overridesNoopDefault() {
        MessagePublisher publisher = context.getBean(MessagePublisher.class);

        assertThat(publisher).isInstanceOf(RabbitMqMessagePublisher.class);
    }

    @Test
    void jackson3ConverterAndTopologyBeansPresent() {
        assertThat(context.getBean(MessageConverter.class)).isNotNull();
        // 主交换 + 死信交换（两个 TopicExchange，按名取）
        assertThat(context.getBean("agentExchange", TopicExchange.class)).isNotNull();
        assertThat(context.getBean("dlxExchange", TopicExchange.class)).isNotNull();
        // 会话持久化队列 + 死信队列
        assertThat(context.getBeansOfType(Queue.class)).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void auditEventQueueAndBindingPresent() {
        // 审计队列绑定到主交换 audit.event 路由键（AuditConsumer 消费此队列；T55 死信拓扑对称）
        assertThat(context.getBean("auditEventQueue", Queue.class)).isNotNull();
        assertThat(context.getBean("auditBinding", Binding.class)).isNotNull();
    }
}
