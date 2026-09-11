package com.agentdemo007.persistence.mq;

/**
 * 消息发布 seam（Phase 13·异步持久化引擎无关接口）。
 *
 * <p>生产者（{@code HistoryPersistProducer}/>{@code AuditProducer}）依赖本接口而非 {@code RabbitTemplate}
 * （§5.14：PipelineStep 契约即引擎无关 seam，换引擎不换收口）。两条装配路径互斥选其一：
 * <ul>
 *   <li>默认（dev / {@code app.rabbitmq.enabled=false}）→ {@link NoopMessagePublisher} 仅记日志、不连 broker、不抛异常。</li>
 *   <li>生产（{@code app.rabbitmq.enabled=true}）→ {@code RabbitMqMessagePublisher} 真实 AMQP 投递（由 {@code RabbitMqConfig} 条件装配）。</li>
 * </ul>
 *
 * <p>实现内部吞掉 MQ 异常（§5.12 能跑通&gt;完美）——不向调用者抛出，停 MQ 时主接口照常 200。
 * 调用者据此可无顾虑发布，无需自裹 try/catch（终端后置钩子仍有兜底 catch，防御纵深）。
 */
public interface MessagePublisher {

    /**
     * 最佳努力投递消息到指定路由键。
     *
     * @param routingKey 路由键（如 {@code session.persist} / {@code audit.event}）
     * @param payload   消息载荷（强类型 record，由 MessageConverter 序列化）
     */
    void publish(String routingKey, Object payload);
}
