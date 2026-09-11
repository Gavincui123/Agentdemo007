package com.agentdemo007.admin;

import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.persistence.entity.ChatTurnEntity;
import com.agentdemo007.persistence.mq.ChatTurnEvent;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理台·会话历史端点单测（Phase 19·T98）。
 *
 * <p>{@code GET /admin/sessions/{sessionId}}：按 {@code sessionId} 列出该会话全部轮次（时间升序），
 * 经 {@link ChatTurnSummary} 强类型透出。无轮次（未知会话/MQ 未落库）→ 返回空列表（②每步降级：端点恒可用，
 * 不 404、不抛——会话是否曾存在与"是否有持久化轮次"不可由 repo 区分，空即诚实作答）。
 *
 * <p>直构控制器 + mock {@link ChatTurnRepository}（聚焦控制器映射与顺序保持）；派生查询的 JPA 语义
 * 由 {@code PersistenceRepositoryTest#chatTurnRepository_findsBySessionIdOrdered} 守卫。
 */
class AdminSessionControllerTest {

    private final ChatTurnRepository repository = mock(ChatTurnRepository.class);
    private final AdminSessionController controller = new AdminSessionController(repository);

    private ChatTurnEntity turn(String traceId, String sessionId, String q, String a, String intent,
                               OffsetDateTime ts) {
        return ChatTurnEntity.from(new ChatTurnEvent(traceId, sessionId, q, a, intent, false, null, ts));
    }

    @Test
    void session_returnsTurnsInOrderAsSummaries() {
        ChatTurnEntity e1 = turn("t1", "s1", "你好", "您好", "CHIT_CHAT",
                OffsetDateTime.parse("2026-09-08T09:00:00Z"));
        ChatTurnEntity e2 = turn("t2", "s1", "查订单", "订单已查到", "ORDER_QUERY",
                OffsetDateTime.parse("2026-09-08T09:05:00Z"));
        when(repository.findBySessionIdOrderByTimestampAsc("s1")).thenReturn(List.of(e1, e2));

        UnifiedResponse resp = controller.session("s1");

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<ChatTurnSummary> data = (List<ChatTurnSummary>) resp.data();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).rawInput()).isEqualTo("你好");
        assertThat(data.get(0).finalReply()).isEqualTo("您好");
        assertThat(data.get(0).sessionId()).isEqualTo("s1");
        assertThat(data.get(0).intent()).isEqualTo("CHIT_CHAT");
        assertThat(data.get(1).rawInput()).isEqualTo("查订单");
        assertThat(data.get(1).timestamp()).isEqualTo(OffsetDateTime.parse("2026-09-08T09:05:00Z"));
    }

    @Test
    void session_noTurns_returnsEmptyListNotError() {
        when(repository.findBySessionIdOrderByTimestampAsc("unknown")).thenReturn(List.of());

        UnifiedResponse resp = controller.session("unknown");

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<ChatTurnSummary> data = (List<ChatTurnSummary>) resp.data();
        assertThat(data).isEmpty();
    }
}
