package com.agentdemo007.capability.business;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品查询服务测试（Slice 1·[[business-tools-workflow-dag]]）。
 *
 * <p>mock 数据供 T1（查商品实时事实→RunTime_*）与 T3（商品推荐：商品+用户+活动政策并行 tool_calls）。
 * {@code availableProducts} 仅返库存 &gt; 0 的商品（缺货不进推荐）。
 */
class ProductQueryServiceTest {

    private final ProductQueryService service = new ProductQueryService();

    @Test
    void findBySku_headphone_returnsRecord() {
        // SKU-001：无线耳机（与 ORD-001 items 一致），有库存
        Optional<ProductRecord> found = service.findBySku("SKU-001");

        assertThat(found).isPresent();
        ProductRecord product = found.get();
        assertThat(product.sku()).isEqualTo("SKU-001");
        assertThat(product.name()).contains("耳机");
        assertThat(product.price()).isEqualByComparingTo(new BigDecimal("299.00"));
        assertThat(product.stock()).isPositive();
        assertThat(product.tags()).isNotEmpty();
    }

    @Test
    void findBySku_outOfStock_returnsRecord() {
        // SKU-002：蓝牙音箱，缺货（不进推荐列表，但单查仍可返）
        Optional<ProductRecord> found = service.findBySku("SKU-002");

        assertThat(found).isPresent();
        ProductRecord product = found.get();
        assertThat(product.stock()).isZero();
    }

    @Test
    void findBySku_notFound_empty() {
        assertThat(service.findBySku("SKU-999")).isEmpty();
    }

    @Test
    void findBySku_null_empty() {
        assertThat(service.findBySku(null)).isEmpty();
    }

    @Test
    void availableProducts_excludesOutOfStock() {
        // T3 推荐：仅返有库存商品，缺货（SKU-002）排除
        List<ProductRecord> available = service.availableProducts();

        assertThat(available).isNotEmpty();
        assertThat(available).allSatisfy(p -> assertThat(p.stock()).isPositive());
        assertThat(available).noneSatisfy(p -> assertThat(p.sku()).isEqualTo("SKU-002"));
    }
}
