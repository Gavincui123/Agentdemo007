package com.agentdemo007.capability.refusal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.refusal.*} 配置绑定（拒答机制·[[refusal-design]]）。
 *
 * <p>拒答 = "无可靠依据时明确告知无法回答"，是业务级正确行为，<b>不是系统降级</b>——
 * 与 {@code DegradationScenario}（面向故障/攻击/超限的话术兜底）语义正交。
 *
 * <p>{@code mode} 三档：
 * <ul>
 *   <li><b>off</b>：不启用拒答闸门（现状行为，仅保留 {@code toolDataMisses} 事实通道）；</li>
 *   <li><b>prompt</b>（默认）：软约束——RAG grounding 未命中时向 System Runtime 块注入
 *       "严禁自身知识补答"强指令，由终答 LLM 执行拒答；工具无数据走「工具无数据」块框定；</li>
 *   <li><b>strict</b>：硬闸门——grounding 未命中且全部证据通道（ragFragments/runtimeFacts/
 *       toolResults）为空时零 LLM 短路 {@code presetReply} 拒答话术。</li>
 * </ul>
 * 生产建议 {@code REFUSAL_MODE=strict}（确定性拒答不依赖模型自觉）；默认 prompt 保持既有
 * 评测黄金集（rag.json 期望 RAG_SKIP 后 LLM 继续作答）行为兼容。
 */
@ConfigurationProperties(prefix = "app.refusal")
public class RefusalProperties {

    /** 拒答模式（off|prompt|strict），默认 prompt（软约束）。 */
    private Mode mode = Mode.PROMPT;

    /** strict 短路拒答话术（面向用户，业务级"无法回答"）。 */
    private String phrase = "很抱歉，知识库中暂未找到可支撑回答的资料，为避免给您错误信息，"
            + "这个问题我无法作答。建议补充订单号等更多信息、换种问法，或转人工客服处理。";

    public enum Mode { OFF, PROMPT, STRICT }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getPhrase() {
        return phrase;
    }

    public void setPhrase(String phrase) {
        this.phrase = phrase;
    }
}
