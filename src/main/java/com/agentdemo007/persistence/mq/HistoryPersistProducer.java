package com.agentdemo007.persistence.mq;

import org.springframework.stereotype.Component;

/**
 * 会话持久化生产者（Phase 13·{@link MessagePublisher} seam 领域封装）。
 *
 * <p>固定路由键 {@link #ROUTING_KEY} + 透传 {@link ChatTurnEvent} 载荷。终端后置钩子
 * {@code ChatTurnFinalizer} 从 {@code PipelineContext}+{@code PipelineResult} 组装事件后调本生产者投递，
 * 消费者 {@code HistoryPersistConsumer} 落库为 {@code ChatTurnEntity}。
 *
 * <p>路由键常量集中于此（拓扑 {@code RabbitMqConfig} 引用本常量建绑定，单一真相源）。
 * 生产者依赖 seam 而非 {@code RabbitTemplate}（§5.14 引擎无关 seam）→ 单测用假替换 seam 即可，无需 broker。
 */
@Component
public class HistoryPersistProducer {

    /** 会话持久化路由键（拓扑绑定 + 生产投递共用此常量）。 */
    public static final String ROUTING_KEY = "session.persist";

    private final MessagePublisher messagePublisher;

    public HistoryPersistProducer(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    /** 投递一轮会话事件到会话持久化路由键（最佳努力，seam 内部吞 MQ 异常，§5.12）。 */
    public void publish(ChatTurnEvent event) {
        messagePublisher.publish(ROUTING_KEY, event);
    }
}
