package com.agentdemo007.capability.business;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单查询服务（外部系统高置信数据·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>mock 实现：canned 订单覆盖 DAG validate 全部分支——本人+7 天内（ORD-001）/ 本人+超 7 天（ORD-002）/
 * 非本人（ORD-003）/ 不存在（ORD-999）。@Tool 包装（Slice 2）与 DAG query_order 节点（Slice 3）共用此 mock 数据源。
 * 订单归属对齐前端真实用户 10086（[[business-tools-workflow-dag]] 数据准确性：mock 须对得上当前用户）。
 * 后期接真订单系统时换 impl 不换 seam/调用方（单点改造，§6 契约变更影响面外）。
 *
 * <p>收口：订单数据真相源只经此服务；未知/null id 幂等返 empty 不抛（§5.12 每步降级，不阻塞主链路）。
 */
@Component
public class OrderQueryService {

    private static final Map<String, OrderRecord> ORDERS = Map.of(
            "ORD-001", new OrderRecord("ORD-001", "10086",
                    Instant.parse("2026-09-09T10:00:00Z"), "已签收",
                    List.of("无线耳机"), new BigDecimal("299.00")),
            "ORD-002", new OrderRecord("ORD-002", "10086",
                    Instant.parse("2026-09-01T10:00:00Z"), "已签收",
                    List.of("蓝牙音箱"), new BigDecimal("199.00")),
            "ORD-003", new OrderRecord("ORD-003", "10010",
                    Instant.parse("2026-09-10T08:00:00Z"), "已签收",
                    List.of("手机壳"), new BigDecimal("39.00")));

    /** 按订单 id 查询（外部系统高置信事实，将路由进 RunTime_* 通道）。 */
    public Optional<OrderRecord> findByOrderId(String orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        // 防御性归一化（trim+大写）：调用方可能传用户原话里的 "ord-001"（精确键会 miss）
        return Optional.ofNullable(ORDERS.get(orderId.trim().toUpperCase(java.util.Locale.ROOT)));
    }
}
