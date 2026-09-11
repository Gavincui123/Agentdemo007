package com.agentdemo007.capability.hitl;

import java.util.Optional;

/**
 * 带外人工决议解析器（引擎无关 seam）。
 *
 * <p>同步请求内工单恒为 PENDING（人工审批在带外异步发生）；prod 实现查询工单存储/队列，
 * 若人工已 APPROVED/REJECTED 则返回对应 {@link HitlDecision.Outcome}，否则返回 {@link Optional#empty()}。
 * dev 落 {@link #none()}（无带外决议→空），使同步链路始终走超时/权限判定。
 */
@FunctionalInterface
public interface DecisionResolver {

    Optional<HitlDecision.Outcome> resolve(HumanTicket ticket);

    /** dev 默认：无带外决议（始终返回空）。 */
    static DecisionResolver none() {
        return ticket -> Optional.empty();
    }
}
