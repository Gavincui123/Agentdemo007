package com.agentdemo007.admin;

import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.persistence.entity.ChatTurnEntity;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理台·会话历史端点（Phase 19·T98）。
 *
 * <p>{@code GET /admin/sessions/{sessionId}}：列出该会话全部轮次（{@code timestamp} 升序），
 * 经 {@link ChatTurnSummary} 强类型对外收口，供前端回放。
 *
 * <p>无轮次（未知会话 / MQ 未落库 / 降级态未持久化）→ 返回<b>空列表</b>（②每步降级：端点恒可用，
 * 不 404、不抛——repo 无法区分"会话从未存在"与"无持久化轮次"，空即诚实作答）。
 * 鉴权在 T103 收口（{@code /admin/*} 应受保护，此处先开放便于联调）。
 */
@RestController
@RequestMapping("/admin/sessions")
public class AdminSessionController {

    private final ChatTurnRepository repository;

    public AdminSessionController(ChatTurnRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{sessionId}")
    public UnifiedResponse session(@PathVariable String sessionId) {
        List<ChatTurnSummary> turns = repository.findBySessionIdOrderByTimestampAsc(sessionId).stream()
                .map(AdminSessionController::toSummary)
                .toList();
        return UnifiedResponse.success(turns);
    }

    private static ChatTurnSummary toSummary(ChatTurnEntity e) {
        return new ChatTurnSummary(e.getId(), e.getTraceId(), e.getSessionId(), e.getRawInput(),
                e.getFinalReply(), e.getIntent(), e.isDegraded(), e.getScenario(), e.getTimestamp());
    }
}
