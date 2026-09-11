package com.agentdemo007.persistence.mq;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineResult;

import java.time.OffsetDateTime;

/**
 * 会话轮次事件（Phase 13·会话持久化 MQ 载荷）。
 *
 * <p>由终端后置钩子 {@code ChatTurnFinalizer} 在流水线结束后从 {@link PipelineContext} +
 * {@link PipelineResult} 组装，投递到 {@code session.persist.queue}，由 {@code HistoryPersistConsumer}
 * 异步落库为 {@code ChatTurnEntity}。MQ 不可用时降级为 best-effort（不阻塞主接口，§5.12 能跑通>完美）。
 *
 * <p>强类型 record（§5.14：禁止各步私造 Map 互相传参）。{@code intent} 取枚举名（稳定序列化标识），
 * 未识别为 {@code null}。
 *
 * @param traceId    链路标识（跨 MQ 消费者贯通，落库可追溯）
 * @param sessionId  会话标识（多轮续）
 * @param rawInput   用户原始输入
 * @param finalReply 终端回复（正常回复或话术）
 * @param intent     意图枚举名（未识别 null）
 * @param degraded   是否经历降级/短路
 * @param scenario   降级场景名（无降级 null）
 * @param timestamp   事件时间（ISO-8601 偏移）
 */
public record ChatTurnEvent(String traceId, String sessionId, String rawInput, String finalReply,
                            String intent, boolean degraded, String scenario, OffsetDateTime timestamp) {

    /** 工厂：从上下文 + 终端结果组装，时间戳取当前。 */
    public static ChatTurnEvent from(PipelineContext context, PipelineResult result) {
        String intent = (context.intent() != null) ? context.intent().name() : null;
        return new ChatTurnEvent(
                context.traceId(),
                context.sessionId(),
                context.rawInput(),
                result.reply(),
                intent,
                result.degraded(),
                result.scenario(),
                OffsetDateTime.now());
    }
}
