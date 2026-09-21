package com.agentdemo007.session;

import com.agentdemo007.capability.kb.KbLevel;

/**
 * 对话主体（Phase 21 等级门全通道覆盖）：userId + 会员等级的不可变快照。
 *
 * <p>为什么存在：{@code PipelineContext} 是流水线内的主体载体，但政策 @Tool 经 langchain4j
 * 反射调用（方法签名只有 LLM 传参）、且韧性装饰器超时模式下工具在守护线程执行——上下文
 * 到不了 seam。本记录经 {@link ChatSubjectHolder}（ToolExecutionStep 窗口内写入/清除）与
 * DAG 节点显式传参两条路把主体带到 {@code PolicyQueryService}。
 *
 * <p>等级语义与 {@code PipelineContext.memberLevel} 同源同口径：唯一来源 = 登录态 + 会员服务，
 * 永不从对话内容取；{@code null} 主体/等级归一匿名 V0（fail-closed，只出公开档）。
 */
public record ChatSubject(String userId, KbLevel memberLevel) {

    /** 匿名主体（eval/未登录/holder 窗口外读取）：等级 V0 fail-closed。 */
    public static final ChatSubject ANONYMOUS = new ChatSubject(null, KbLevel.V0);

    public ChatSubject {
        memberLevel = (memberLevel != null) ? memberLevel : KbLevel.V0;
    }

    /** 装配工厂（null 归一：userId 可空，等级 null 收敛 V0）。 */
    public static ChatSubject of(String userId, KbLevel memberLevel) {
        return new ChatSubject(userId, memberLevel);
    }
}
