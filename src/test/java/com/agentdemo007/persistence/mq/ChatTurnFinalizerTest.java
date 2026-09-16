package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.observability.audit.AuditEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 会话终端后置钩子测评（Phase 13·{@link ChatTurnFinalizer}）。
 *
 * <p>验证终端后置钩子把 {@link PipelineContext}+{@link PipelineResult} 收口为 {@link ChatTurnEvent}
 * 投递会话持久化、并刷出 {@code context.auditEvents()} 到审计路由键；history/audit 各自独立 try-catch
 * ——一方失败不跳过另一方（§5.11 审计不丢）、且不向主接口传播异常（§5.12 停 MQ→主接口 200）。
 *
 * <p>用真实 {@link HistoryPersistProducer}/{@link AuditProducer} + {@link CapturingMessagePublisher}
 * 假替换 seam（不连 broker），验证路由键 + 载荷透传 + 时序。
 */
class ChatTurnFinalizerTest {

    @Test
    void finalizeTurn_publishesChatTurnEventAndFlushesAuditEventsInOrder() {
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));

        PipelineContext context = new PipelineContext("trace-1", "sess-1", "你好");
        AuditEvent audit1 = AuditEvent.of(AuditEventType.INJECTION, "trace-1", "sess-1", "注入命中");
        AuditEvent audit2 = AuditEvent.of(AuditEventType.TOOL_FAILURE, "trace-1", "sess-1", "工具失败");
        context.addAuditEvent(audit1);
        context.addAuditEvent(audit2);

        finalizer.finalizeTurn(context, PipelineResult.ok("您好"));

        // 1 条会话持久化 + 2 条审计，按序
        assertThat(publisher.publishCount()).isEqualTo(3);
        assertThat(publisher.published().get(0).getKey()).isEqualTo(HistoryPersistProducer.ROUTING_KEY);
        assertThat(publisher.published().get(1).getKey()).isEqualTo(AuditProducer.ROUTING_KEY);
        assertThat(publisher.published().get(2).getKey()).isEqualTo(AuditProducer.ROUTING_KEY);

        // 会话载荷收口自 context + result
        ChatTurnEvent turn = (ChatTurnEvent) publisher.published().get(0).getValue();
        assertThat(turn.traceId()).isEqualTo("trace-1");
        assertThat(turn.sessionId()).isEqualTo("sess-1");
        assertThat(turn.rawInput()).isEqualTo("你好");
        assertThat(turn.finalReply()).isEqualTo("您好");
        assertThat(turn.degraded()).isFalse();

        // 审计载荷原样透传、保序
        assertThat(publisher.published().get(1).getValue()).isEqualTo(audit1);
        assertThat(publisher.published().get(2).getValue()).isEqualTo(audit2);
    }

    @Test
    void finalizeTurn_historyFailureStillFlushesAuditAndDoesNotPropagate() {
        // 投递会话持久化路由键时模拟 MQ 不可用；审计路由键正常——验证两路独立、审计不丢、不传播
        CapturingMessagePublisher publisher = new CapturingMessagePublisher() {
            @Override
            public void publish(String routingKey, Object payload) {
                if (HistoryPersistProducer.ROUTING_KEY.equals(routingKey)) {
                    throw new RuntimeException("MQ 不可用");
                }
                super.publish(routingKey, payload);
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));

        PipelineContext context = new PipelineContext("trace-2", "sess-2", "你好");
        context.addAuditEvent(AuditEvent.of(AuditEventType.INJECTION, "trace-2", "sess-2", "注入"));

        assertThatCode(() -> finalizer.finalizeTurn(context, PipelineResult.ok("您好")))
                .as("停 MQ/投递异常不传播到主接口")
                .doesNotThrowAnyException();

        // 会话持久化被跳过，但审计事件仍刷出（独立 try-catch，§5.11 审计不丢）
        assertThat(publisher.published()).hasSize(1);
        assertThat(publisher.published().get(0).getKey()).isEqualTo(AuditProducer.ROUTING_KEY);
    }

    @Test
    void finalizeTurn_recordsMqPublishCountersPerChannel() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher), metrics);

        PipelineContext context = new PipelineContext("trace-3", "sess-3", "你好");
        context.addAuditEvent(AuditEvent.of(AuditEventType.INJECTION, "trace-3", "sess-3", "注入"));
        context.addAuditEvent(AuditEvent.of(AuditEventType.TOOL_FAILURE, "trace-3", "sess-3", "工具失败"));

        finalizer.finalizeTurn(context, PipelineResult.ok("您好"));

        // 两路均成功：history/audit 各记一次（按 flush 计数，非按事件条数——SLI 是投递路径成功率）
        assertThat(registry.counter("agent.mq", "channel", "history", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.mq", "channel", "audit", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.mq", "channel", "history", "success", "false").count()).isZero();
        assertThat(registry.counter("agent.mq", "channel", "audit", "success", "false").count()).isZero();
    }

    @Test
    void finalizeTurn_historyFailureRecordsFalseButAuditStillSucceeds() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        CapturingMessagePublisher publisher = new CapturingMessagePublisher() {
            @Override
            public void publish(String routingKey, Object payload) {
                if (HistoryPersistProducer.ROUTING_KEY.equals(routingKey)) {
                    throw new RuntimeException("MQ 不可用");
                }
                super.publish(routingKey, payload);
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher), metrics);

        PipelineContext context = new PipelineContext("trace-4", "sess-4", "你好");
        context.addAuditEvent(AuditEvent.of(AuditEventType.INJECTION, "trace-4", "sess-4", "注入"));

        finalizer.finalizeTurn(context, PipelineResult.ok("您好"));

        // history 失败、audit 成功（独立 try-catch，§5.11 审计不丢）
        assertThat(registry.counter("agent.mq", "channel", "history", "success", "false").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.mq", "channel", "audit", "success", "true").count()).isEqualTo(1.0);
    }

    @Test
    void finalizeTurn_appendsUserAndAiToSessionCache() {
        // 修复接线：SessionCacheService.append 此前零调用（历史只读不写，每轮都当"新建会话"）——
        // 终端钩子负责把 [User(rawInput), Ai(finalReply)] 写入会话缓存
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        com.agentdemo007.session.cache.SessionCacheService cache =
                org.mockito.Mockito.mock(com.agentdemo007.session.cache.SessionCacheService.class);
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher),
                AgentMetrics.NO_OP, cache);

        PipelineContext context = new PipelineContext("trace-5", "sess-5", "查订单");
        finalizer.finalizeTurn(context, PipelineResult.ok("订单已查到"));

        org.mockito.Mockito.verify(cache).append(org.mockito.ArgumentMatchers.eq("sess-5"),
                org.mockito.ArgumentMatchers.argThat(msgs -> msgs.size() == 2
                        && msgs.get(0) instanceof com.agentdemo007.session.model.ChatMessage.User
                        && msgs.get(0).content().equals("查订单")
                        && msgs.get(1) instanceof com.agentdemo007.session.model.ChatMessage.Ai
                        && msgs.get(1).content().equals("订单已查到")));
    }

    @Test
    void finalizeTurn_userCancelled_skipsHistoryAppend() {
        // 用户"停止对话"（USER_CANCELLED，未产出真实回复）→ 不写入会话历史
        CapturingMessagePublisher publisher = new CapturingMessagePublisher();
        com.agentdemo007.session.cache.SessionCacheService cache =
                org.mockito.Mockito.mock(com.agentdemo007.session.cache.SessionCacheService.class);
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher),
                AgentMetrics.NO_OP, cache);

        PipelineContext context = new PipelineContext("trace-6", "sess-6", "查订单");
        finalizer.finalizeTurn(context, com.agentdemo007.common.pipeline.PipelineResult.shortCircuit(
                "已停止本轮处理。", com.agentdemo007.common.degradation.DegradationScenario.USER_CANCELLED));

        org.mockito.Mockito.verifyNoInteractions(cache);
    }
}
