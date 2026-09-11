package com.agentdemo007.config;

import com.agentdemo007.observability.trace.TraceContextPropagator;
import com.agentdemo007.persistence.mq.AuditProducer;
import com.agentdemo007.persistence.mq.HistoryPersistProducer;
import com.agentdemo007.persistence.mq.MessagePublisher;
import com.agentdemo007.persistence.mq.RabbitMqMessagePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 异步持久化生产装配（Phase 13）。
 *
 * <p>镜像 {@link RedisConfig} 条件装配模式：{@code app.rabbitmq.enabled=true} 时启用真实 AMQP 后端，
 * 定义 exchange/queue/binding/DLQ + Jackson 3 消息转换器 + 真实 {@link RabbitMqMessagePublisher}；
 * dev 默认（{@code enabled=false} / 缺省）由 {@code NoopMessagePublisher} 兜底，不连 broker。
 *
 * <p>路由拓扑：{@code agent.exchange}（topic）按路由键分发——{@code session.persist}→会话持久化队列、
 * {@code audit.event}→审计队列；消费失败经 {@code agent.dlx} 死信到 {@code agent.dlq}（Phase 13 T55 消费者）。
 *
 * <p>停 MQ 不影响主接口：dev 默认不装配本类→无 {@code RabbitTemplate} 强制连接；生产装配后投递异常
 * 由 {@link RabbitMqMessagePublisher} 吞而不抛（§5.12）。Queue/Exchange 为元数据 bean，不在启动期强制连接 broker
 * （{@code RabbitAdmin} 懒声明，首次连接时才声明）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    /** 主交换（topic，durable）。 */
    public static final String EXCHANGE = "agent.exchange";
    /** 会话持久化队列。 */
    public static final String SESSION_PERSIST_QUEUE = "session.persist.queue";
    /** 审计事件队列。 */
    public static final String AUDIT_EVENT_QUEUE = "audit.event.queue";
    /** 死信交换。 */
    public static final String DLX_EXCHANGE = "agent.dlx";
    /** 死信队列。 */
    public static final String DLQ_QUEUE = "agent.dlq";

    @Bean
    TopicExchange agentExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    TopicExchange dlxExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue sessionPersistQueue() {
        return QueueBuilder.durable(SESSION_PERSIST_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", HistoryPersistProducer.ROUTING_KEY)
                .build();
    }

    @Bean
    Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    Queue auditEventQueue() {
        // 死信拓扑对称会话持久化队列：消费失败同样死信到 DLQ，不阻塞审计消费（§5.12）
        return QueueBuilder.durable(AUDIT_EVENT_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", AuditProducer.ROUTING_KEY)
                .build();
    }

    @Bean
    Binding sessionPersistBinding() {
        return BindingBuilder.bind(sessionPersistQueue())
                .to(agentExchange())
                .with(HistoryPersistProducer.ROUTING_KEY);
    }

    @Bean
    Binding auditBinding() {
        return BindingBuilder.bind(auditEventQueue())
                .to(agentExchange())
                .with(AuditProducer.ROUTING_KEY);
    }

    @Bean
    Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue())
                .to(dlxExchange())
                .with("#");
    }

    /** Jackson 3 消息转换器（强类型 record 载荷 JSON 序列化；Boot 4/Jackson 3 对齐）。 */
    @Bean
    MessageConverter jacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    MessagePublisher rabbitMqMessagePublisher(RabbitTemplate rabbitTemplate, MessageConverter converter,
                                               TraceContextPropagator propagator) {
        rabbitTemplate.setMessageConverter(converter);
        log.info("RabbitMQ 异步持久化已启用（exchange={}，会话队列={}，死信={}）",
                EXCHANGE, SESSION_PERSIST_QUEUE, DLQ_QUEUE);
        return new RabbitMqMessagePublisher(rabbitTemplate, EXCHANGE, propagator);
    }
}
