package com.agentdemo007.capability.business;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品记录（外部系统高置信数据 carrier·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>tags 供 T3 推荐话术整合（热销/满减/配件等）；stock 决定是否进推荐列表（缺货排除）。
 */
public record ProductRecord(String sku, String name, BigDecimal price, int stock, List<String> tags) {
}
