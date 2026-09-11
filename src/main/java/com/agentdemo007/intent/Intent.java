package com.agentdemo007.intent;

/**
 * 用户意图（第三层·意图识别的输出契约）。
 *
 * <p>Phase 7 完整实现多层级识别，Phase 4 先以枚举承载路由选择策略的输入类型契约
 * （意图↔模型路由规则）。枚举值与 §5.12 降级 + eval 数据对齐。
 */
public enum Intent {
    CHIT_CHAT("闲聊"),
    REASONING("推理"),
    LONG_CONTEXT("长上下文"),
    STRUCTURED_EXTRACTION("结构化抽取"),
    INJECTION("注入"),
    TRANSFER_TO_HUMAN("转人工"),
    OTHER("未知");

    private final String description;

    Intent(String description) { this.description = description; }

    public String description() { return description; }
}
