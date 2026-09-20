package com.agentdemo007.capability.tool;

import java.util.List;

/**
 * 工具"无数据"信号识别（[[refusal-design]]·工具分支拒答配套）。
 *
 * <p><b>背景</b>：工具连通且执行成功 ≠ 查到了数据。订单不存在、商品无匹配、政策库无该条目时，
 * @Tool 返回的是"未命中"事实文案——过去它与真实数据同等进入 {@code runtimeFacts}，是否如实转告
 * 全靠终答 LLM 自觉，存在"拿相近数据凑数/编造"风险。本类把这些未命中文案从正常结果中识别出来，
 * {@link ToolExecutionStep} 据此额外路由进 {@code context.toolDataMisses}（「工具无数据」块，
 * 框定终答必须如实告知未查到）。
 *
 * <p><b>契约</b>：@Tool 的"无数据"返回文案须命中 {@link #MISS_PATTERNS} 之一（现有工具已满足：
 * "订单 X 不存在" / "无可售商品匹配「X」" / "商品 X 不存在" / "当前无可售商品"）。工具返回的
 * 无数据文案<b>仍然是真实事实</b>，保留在 runtimeFacts 高置信通道不变——本类只做<b>附加</b>路由，
 * 不做替换。
 */
public final class NoDataSignals {

    /** 无数据文案特征（子串匹配，面向现有 @Tool 返回口径；新增无数据工具须对齐）。 */
    private static final List<String> MISS_PATTERNS = List.of(
            "不存在",
            "无可售商品",
            "无匹配",
            "未查询到",
            "查无此",
            "暂无相关");

    private NoDataSignals() {
    }

    /** 判断工具成功返回的文案是否为"未命中数据"事实（null/blank 恒 false——空内容走错误口径）。 */
    public static boolean isNoData(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (String p : MISS_PATTERNS) {
            if (content.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
