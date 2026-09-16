package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.ProductQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品查询 @Tool（[[business-tools-workflow-dag]] §2.2·RUNTIME 通道）。
 *
 * <p>委托 {@link ProductQueryService}（mock 数据）；结果路由进 {@code runtimeFacts}。
 * T1（实时事实）+ T3 商品推荐（可售商品列表，缺货排除）共用底层服务 mock 数据源。
 */
@Component
public class ProductQueryTool {

    private final ProductQueryService productService;

    public ProductQueryTool(ProductQueryService productService) {
        this.productService = productService;
    }

    @Tool("按商品SKU查询单个商品详情，或查询全部可售商品（SKU 为空时返回可售列表）")
    @ToolChannel(ToolCategory.RUNTIME)
    public String queryProduct(@P("商品SKU（例如 SKU-001）；留空则返回可售商品列表") String sku) {
        if (sku == null || sku.isBlank()) {
            List<String> lines = productService.availableProducts().stream()
                    .map(p -> "商品 " + p.sku() + "：" + p.name() + "，价格 " + p.price()
                            + "，库存 " + p.stock() + "，标签" + p.tags())
                    .collect(Collectors.toList());
            return lines.isEmpty() ? "当前无可售商品" : "可售商品：\n" + String.join("\n", lines);
        }
        return productService.findBySku(sku)
                .map(p -> "商品 " + p.sku() + "：" + p.name() + "，价格 " + p.price()
                        + "，库存 " + p.stock() + "，标签" + p.tags())
                .orElse("商品 " + sku + " 不存在");
    }
}
