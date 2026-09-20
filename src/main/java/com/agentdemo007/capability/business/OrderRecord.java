package com.agentdemo007.capability.business;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 订单记录（外部系统高置信数据 carrier·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>typed mock 数据：@Tool 包装（RunTime_* 通道）与 DAG query_order 节点共用同一份。
 * orderTime 为订单实时事实之一（2026-09-19 生产化：随政策知识一并交 Agent 资格裁决）。
 */
public record OrderRecord(
        String orderId,
        String userId,
        Instant orderTime,
        String status,
        List<String> items,
        BigDecimal amount) {
}
