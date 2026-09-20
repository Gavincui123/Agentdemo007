package com.agentdemo007.persistence.repository;

import com.agentdemo007.persistence.entity.BizOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 业务订单仓库（L2 HITL 业务前置校验·2026-09-18）：{@code HitlBusinessGate} 按
 * 订单号（幂等键实体段）对账支付/物流状态。自然主键 {@code orderId}，无派生查询需求。
 */
public interface BizOrderRepository extends JpaRepository<BizOrderEntity, String> {
}
