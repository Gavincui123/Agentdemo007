package com.agentdemo007.persistence.mq;

import com.agentdemo007.config.RabbitMqConfig;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.trace.TraceContextPropagator;
import com.agentdemo007.persistence.entity.AuditEventEntity;
import com.agentdemo007.persistence.repository.AuditEventRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计事件消费者（Phase 13·手动 ack 模式）。
 *
 * <p>消费 {@link RabbitMqConfig#AUDIT_EVENT_QUEUE}：成功落库→{@code basicAck}；
 * 持久化失败→{@code basicNack(requeue=false)} 死信到 DLQ（§5.12 不阻塞消费）。
 * 单测以 mock {@link Channel}+{@link AuditEventRepository} 直接调用 {@link #onAuditEvent} 验证语义 + 载荷映射。
 *
 * <p>关键事件（注入/工具/HITL/故障转移/异常）全量留痕（§5.11 可审计性）——本类确保审计链路落库，
 * 落库失败死信不丢（DLQ 兜底）、不阻塞后续审计消费。
 *
 * <p>{@code @ConditionalOnProperty(enabled=true)} → dev 默认（{@code enabled=false}/缺省）不装配，
 * 无监听容器启动、不连 broker。
 */
@Component
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true")
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);

    private final AuditEventRepository repository;
    private final TraceContextPropagator propagator;

    public AuditConsumer(AuditEventRepository repository) {
        this(repository, TraceContextPropagator.NO_OP);
    }

    @Autowired
    public AuditConsumer(AuditEventRepository repository, TraceContextPropagator propagator) {
        this.repository = repository;
        this.propagator = propagator;
    }

    /**
     * 消费一条审计事件并落库。手动 ack：成功→ack；任意异常→nack(requeue=false) 死信，不抛、不阻塞。
     *
     * <p>跨进程 traceId 不断（Phase 15）：入站消息的 {@code traceparent} 头经 {@link TraceContextPropagator}
     * 在消费线程恢复为 current traceId（写 MDC），使落库期间 {@code TraceId.current()} 与生产侧一致。
     * scope 关闭还原 MDC（finally，best-effort，②不抛）。
     *
     * @param event       审计载荷（{@link AuditEventEntity#from(AuditEvent)} 映射落库）
     * @param channel     broker 通道（手动 ack/nack）
     * @param tag         投递标签（{@link AmqpHeaders#DELIVERY_TAG}，监听容器注入；单测直传）
     * @param traceparent W3C trace 头（{@link TraceContextPropagator#TRACEPARENT_HEADER}，缺失时 no-op scope）
     */
    @RabbitListener(queues = RabbitMqConfig.AUDIT_EVENT_QUEUE)
    public void onAuditEvent(AuditEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag,
                             @Header(value = TraceContextPropagator.TRACEPARENT_HEADER, required = false) String traceparent) {
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null) {
            carrier.put(TraceContextPropagator.TRACEPARENT_HEADER, traceparent);
        }
        AutoCloseable scope = propagator.restoreScope(carrier);
        try {
            repository.save(AuditEventEntity.from(event));
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.warn("审计事件落库失败，死信到 DLQ：type={} traceId={}",
                    event.type(), event.traceId(), e);
            try {
                channel.basicNack(tag, false, false);
            } catch (IOException nackEx) {
                log.warn("basicNack 失败 tag={}", tag, nackEx);
            }
        } finally {
            try {
                scope.close();
            } catch (Exception closeEx) {
                // ② best-effort：trace scope 关闭异常（MDC 还原）不影响落库结果
                log.debug("trace scope 关闭异常（忽略）tag={}", tag, closeEx);
            }
        }
    }
}
