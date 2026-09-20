package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.ProductQueryService;
import com.agentdemo007.capability.business.ProductRecord;
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

    /**
     * 2026-09-17 定案：推荐/查询类问题必须传关键词做相关性过滤（实测「耳机推荐」因全量倾倒
     * 目录连不相关手机壳一起推）；仅 SKU 与关键词<b>都</b>缺失才返回全量列表（如「有什么商品」）。
     * 无匹配如实返回，不回退全量（防模型拿不相关商品凑数）。
     */
    @Tool("按商品SKU查询单个商品详情；或按关键词查询可售商品（如：耳机、手机壳、配件——只返回与用户问题相关的商品）；SKU与关键词都留空时才返回全部可售商品")
    @ToolChannel(ToolCategory.RUNTIME)
    public String queryProduct(@P("商品SKU（例如 SKU-001）") String sku,
                               @P("商品名/标签关键词（例如：耳机、手机壳）；用户问推荐或查询某类商品时必填，仅在问全部商品时留空") String keyword) {
        if (sku != null && !sku.isBlank()) {
            return productService.findBySku(sku)
                    .map(p -> "商品 " + p.sku() + "：" + p.name() + "，价格 " + p.price()
                            + "，库存 " + p.stock() + "，标签" + p.tags())
                    .orElse("商品 " + sku + " 不存在");
        }
        if (keyword != null && !keyword.isBlank()) {
            List<ProductRecord> matches = productService.searchAvailable(keyword);
            if (matches.isEmpty()) {
                return "无可售商品匹配「" + keyword.trim() + "」";
            }
            return "可售商品：\n" + String.join("\n", productLines(matches));
        }
        List<String> lines = productLines(productService.availableProducts());
        return lines.isEmpty() ? "当前无可售商品" : "可售商品：\n" + String.join("\n", lines);
    }

    private static List<String> productLines(List<ProductRecord> products) {
        return products.stream()
                .map(p -> "商品 " + p.sku() + "：" + p.name() + "，价格 " + p.price()
                        + "，库存 " + p.stock() + "，标签" + p.tags())
                .collect(Collectors.toList());
    }
}
