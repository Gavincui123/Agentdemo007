package com.agentdemo007.persistence.mq;

import com.agentdemo007.observability.audit.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审计事件生产者（Phase 13·{@link MessagePublisher} seam 领域封装）。
 *
 * <p>固定路由键 {@link #ROUTING_KEY} + 透传 {@link AuditEvent} 载荷。两条调用路径：
 * <ul>
 *   <li>终端后置钩子 {@code ChatTurnFinalizer} 刷出 {@code context.auditEvents()} 经 {@link #publishEach} 批量投递。</li>
 *   <li>接入层注入等预流水线审计点（无 context）直接调 {@link #publish(AuditEvent)} 单条投递。</li>
 * </ul>
 * 消费者 {@code AuditConsumer} 落库为 {@code AuditEventEntity}，关键事件全量留痕（§5.11）。
 *
 * <p>路由键常量集中于此（拓扑 {@code RabbitMqConfig} 引用本常量建绑定）。生产者依赖 seam 而非
 * {@code RabbitTemplate}（§5.14 引擎无关 seam）→ 单测用假替换 seam 即可，无需 broker。
 */
@Component
public class AuditProducer {

    /** 审计事件路由键（拓扑绑定 + 生产投递共用此常量）。 */
    public static final String ROUTING_KEY = "audit.event";

    private final MessagePublisher messagePublisher;

    public AuditProducer(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    /** 投递单条审计事件（接入层注入等预流水线审计点常用；seam 内部吞 MQ 异常，§5.12）。 */
    public void publish(AuditEvent event) {
        if (event == null) {
            return;
        }
        messagePublisher.publish(ROUTING_KEY, event);
    }

    /** 批量刷出审计事件（终端后置钩子刷出 context.auditEvents 用；按序逐条投递，保持时序）。 */
    public void publishEach(List<AuditEvent> events) {
        if (events == null) {
            return;
        }
        for (AuditEvent event : events) {
            publish(event);
        }
    }
}
