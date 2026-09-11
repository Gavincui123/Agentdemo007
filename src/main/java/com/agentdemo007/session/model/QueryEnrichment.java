package com.agentdemo007.session.model;

import java.util.List;

/**
 * 查询补全槽（第二层·Phase 20 约束改写产出，强类型收口）。
 *
 * <p>承载从用户查询**抽取**的精确词（订单号/型号/发票类型）与时间线——只补全不替换、
 * 只抽取事实**不下业务结论**（不替用户判定意图归属/订单状态/业务决策）。原查询经
 * {@code PipelineContext.rawInput()} 保留不丢，本槽只附补充上下文，不覆盖 {@code standardQuery}。
 *
 * <p>供 Hybrid RAG 关键词/规则路由通道消费（T89：精确词走精确通道不走纯语义漂移）。
 * {@link #EMPTY} 为空补全（无精确词、无时间线），降级/无信号时复用。
 *
 * @param keywords 精确词（ASCII 订单号/型号 + CJK 词典精确词如发票类型）；保序去重
 * @param timeline 时间线（Q1-Q4/20xx年/上个月 等；无则 null）
 */
public record QueryEnrichment(List<String> keywords, String timeline) {

    public static final QueryEnrichment EMPTY = new QueryEnrichment(List.of(), null);

    public QueryEnrichment {
        keywords = (keywords == null) ? List.of() : List.copyOf(keywords);
    }
}
