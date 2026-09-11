package com.agentdemo007.persistence.repository;

import com.agentdemo007.persistence.entity.ChatTurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话轮次持久化 Repository（Phase 13）。
 *
 * <p>{@code HistoryPersistConsumer} 消费 {@code session.persist} 消息后经此落库 {@link ChatTurnEntity}。
 * Spring Data JPA 自动生成实现；停 MQ 不影响主接口（消费者异步、投递吞而不抛，§5.12）。
 */
public interface ChatTurnRepository extends JpaRepository<ChatTurnEntity, Long> {

    /**
     * 按 {@code sessionId} 查全部轮次、{@code timestamp} 升序（Phase 19·管理台会话回放）。
     *
     * <p>派生查询（Spring Data JPA 按方法名生成）；启动期校验方法名→字段名，违例上下文启动失败。
     */
    List<ChatTurnEntity> findBySessionIdOrderByTimestampAsc(String sessionId);

    /**
     * 降级轮次总数（Phase 19·T99 可观测快照消费）。
     *
     * <p>派生查询：{@code where degraded = true} 计数。供 {@code ObservabilitySummaryCollector}
     * 派生 {@code degradedTurns}——区分"被降级的轮次占比"与"降级事件计数"（后者按场景维度埋在
     * {@code agent.degradation} 计数器，前者是持久化事实）。启动期校验方法名→字段名，违例上下文启动失败。
     */
    long countByDegradedTrue();
}
