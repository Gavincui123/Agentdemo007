package com.agentdemo007.capability.hitl;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工工单服务（第四层·HITL 工单创建与管理）。
 *
 * <p>触发 HITL 时 {@code createTicket} 挂起一张 PENDING 工单；人工审批经 {@code resolve}
 * 流转到 APPROVED/REJECTED；超时经 {@code markTimeout} 流转到 TIMEOUT。
 * 内存实现（{@link ConcurrentHashMap}），prod 由 DB 持久化覆盖（Phase 13 异步持久化 + 审计接入）。
 *
 * <p>收口：HITL 工单的数据真相源只经此服务；未知 id 解析幂等不抛异常（不阻塞主链路，§5.12 每步降级）。
 */
@Component
public class HumanTicketService {

    private final ConcurrentHashMap<String, HumanTicket> tickets = new ConcurrentHashMap<>();

    /** 创建 PENDING 工单。 */
    public HumanTicket createTicket(HitlRequest request, Instant createdAt) {
        HumanTicket ticket = new HumanTicket(
                UUID.randomUUID().toString(),
                request.sessionId(),
                request.query(),
                request.reason(),
                HumanTicket.Status.PENDING,
                createdAt,
                null);
        tickets.put(ticket.id(), ticket);
        return ticket;
    }

    /** 按工单 id 查询。 */
    public Optional<HumanTicket> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tickets.get(id));
    }

    /** 仅 PENDING 工单（供运维管理台展示，Phase 17 前端消费）。 */
    public List<HumanTicket> pendingTickets() {
        List<HumanTicket> result = new ArrayList<>();
        for (HumanTicket t : tickets.values()) {
            if (t.status() == HumanTicket.Status.PENDING) {
                result.add(t);
            }
        }
        return result;
    }

    /** 全量工单（测试/管理台用）。 */
    public List<HumanTicket> all() {
        return new ArrayList<>(tickets.values());
    }

    /** 解析工单（APPROVED/REJECTED），未知 id 幂等返回。 */
    public void resolve(String id, HumanTicket.Status decision, Instant resolvedAt) {
        HumanTicket existing = (id == null) ? null : tickets.get(id);
        if (existing == null) {
            return;
        }
        tickets.put(id, new HumanTicket(
                existing.id(), existing.sessionId(), existing.query(), existing.reason(),
                decision, existing.createdAt(), resolvedAt));
    }

    /** 标记超时，未知 id 幂等返回。 */
    public void markTimeout(String id, Instant resolvedAt) {
        resolve(id, HumanTicket.Status.TIMEOUT, resolvedAt);
    }
}
