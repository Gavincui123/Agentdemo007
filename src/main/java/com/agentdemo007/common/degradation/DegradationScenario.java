package com.agentdemo007.common.degradation;

/**
 * 降级场景枚举。每个场景对应一句面向用户的预设话术，
 * 用于 4xx / 注入 / 攻击 / 依赖故障等情况下短路返回"正常"对话回复。
 */
public enum DegradationScenario {

    INJECTION("抱歉，我无法处理这类请求，请换一种方式提问。"),
    BAD_REQUEST("您的问题我没能完全理解，能再描述清楚一些吗？"),
    PAYLOAD_TOO_LARGE("您的消息有些长，请精简后再发送。"),
    RATE_LIMITED("当前咨询人数较多，请稍后再试。"),
    SESSION_DOWN("服务暂时繁忙，正在为您恢复，请稍后再试。"),
    MODEL_DOWN("我暂时无法回应，请稍后重试。"),
    FAILOVER_EXHAUSTED("服务暂时不可用，请稍后重试。"),
    UNKNOWN_INTENT("这个问题我还在学习，能否换种问法？"),
    TOOL_FAILURE("该操作暂时无法完成，请稍后重试。"),
    RAG_SKIP("暂未检索到相关资料，我基于已有信息为您答复。"),
    HITL_TIMEOUT("您的请求需要人工确认，已为您转接，请耐心等待。"),
    WORKFLOW_APPROVAL_TIMEOUT("您的退款请求已提交，正在等待人工审批，请留意后续通知。"),
    OUTPUT_FALLBACK("该请求的结构化输出暂时不可用，请稍后重试。"),
    PIPELINE_TIMEOUT("当前处理时间较长，请稍后重试。"),
    USER_CANCELLED("已停止本轮处理。"),
    INTERNAL("服务开小差了，请稍后重试。");

    private final String phrase;

    DegradationScenario(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
