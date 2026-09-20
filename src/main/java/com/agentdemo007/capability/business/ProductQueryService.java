package com.agentdemo007.capability.business;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品查询服务（外部系统高置信数据·[[business-tools-workflow-dag]] §2.1）。
 *
 * <p>mock 实现：SKU-001 无线耳机（有库存，与 ORD-001 items 一致）/ SKU-002 蓝牙音箱（缺货）/
 * SKU-003 手机壳（有库存，满减）。{@code availableProducts} 仅返库存 &gt; 0 商品（缺货不进 T3 推荐列表）。
 * 后期接真商品系统换 impl 不换 seam。
 *
 * <p>收口：商品数据真相源只经此服务；未知/null 幂等返 empty 不抛（§5.12 每步降级）。
 */
@Component
public class ProductQueryService {

    private static final Map<String, ProductRecord> PRODUCTS = Map.of(
            "SKU-001", new ProductRecord("SKU-001", "无线耳机", new BigDecimal("299.00"), 50, List.of("电子产品", "热销")),
            "SKU-002", new ProductRecord("SKU-002", "蓝牙音箱", new BigDecimal("199.00"), 0, List.of("电子产品")),
            "SKU-003", new ProductRecord("SKU-003", "手机壳", new BigDecimal("39.00"), 100, List.of("配件", "满减")));

    /** 按 sku 查询（外部系统高置信事实，将路由进 RunTime_* 通道）。 */
    public Optional<ProductRecord> findBySku(String sku) {
        if (sku == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PRODUCTS.get(sku));
    }

    /** 可售商品（库存 > 0），供 T3 商品推荐场景。 */
    public List<ProductRecord> availableProducts() {
        List<ProductRecord> result = new ArrayList<>();
        for (ProductRecord p : PRODUCTS.values()) {
            if (p.stock() > 0) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * 按关键词过滤可售商品（name/tags contains，大小写不敏感）——2026-09-17 定案：推荐场景
     * 不得全量倾倒目录（「耳机推荐」连不相关手机壳一起推）；无匹配返空列表，由调方如实告知。
     */
    public List<ProductRecord> searchAvailable(String keyword) {
        String k = (keyword == null) ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        List<ProductRecord> result = new ArrayList<>();
        for (ProductRecord p : availableProducts()) {
            boolean nameHit = p.name().toLowerCase(java.util.Locale.ROOT).contains(k);
            boolean tagHit = p.tags().stream()
                    .anyMatch(t -> t.toLowerCase(java.util.Locale.ROOT).contains(k));
            if (nameHit || tagHit) {
                result.add(p);
            }
        }
        return result;
    }
}
