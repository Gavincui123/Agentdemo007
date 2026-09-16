package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.OrderQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 订单查询 @Tool（[[business-tools-workflow-dag]] §2.2·RUNTIME 通道）。
 *
 * <p>委托 {@link OrderQueryService}（mock 数据，后期接真订单系统换 impl 不换 seam）；
 * 结果路由进 {@code runtimeFacts}（System 锚点层 Runtime 块高置信事实，非 toolResults）。
 * T1（实时事实）+ DAG query_order 节点共用底层服务 mock 数据源。
 */
@Component
public class OrderQueryTool {

    private final OrderQueryService orderService;

    public OrderQueryTool(OrderQueryService orderService) {
        this.orderService = orderService;
    }

    @Tool("按订单号查询订单详情：状态、商品、金额、下单时间")
    @ToolChannel(ToolCategory.RUNTIME)
    public String queryOrder(@P("订单号，例如 ORD-001") String orderId) {
        return orderService.findByOrderId(orderId)
                .map(o -> "订单 " + o.orderId() + "：用户 " + o.userId()
                        + "，下单时间 " + o.orderTime() + "，状态" + o.status()
                        + "，商品" + o.items() + "，金额 " + o.amount())
                .orElse("订单 " + orderId + " 不存在");
    }
}
