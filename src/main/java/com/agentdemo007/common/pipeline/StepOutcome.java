package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationScenario;

/**
 * 统一步骤产出契约（收口三件套之二）。
 *
 * <p>每个流水线步骤返回同一个 sealed 类型，四种产出，杜绝各步"各自抛异常/各自返回"的不一致：
 * <ul>
 *   <li>{@link Proceed} — 继续下一步（上下文已由本步更新）。</li>
 *   <li>{@link ShortCircuit} — 话术短路：跳过所有后续步骤、零 LLM、审计后返回预设话术（§5.11）。</li>
 *   <li>{@link Degrade} — 降级兜底但继续推进（如改写失败回退原问题、RAG 跳过；§5.12）。</li>
 *   <li>{@link Retry} — 重试本步：图编排下自循环回本节点再执行（工具自纠正，§5.13）；
 *       线性编排下等价 Proceed（线性模式不图循环，重试在各 step 内部自理）。由平台
 *       {@code maxIterations} 护栏兜底死循环（§5.13）。</li>
 * </ul>
 *
 * <p>本契约把「①话术短路 + ②每步降级 + ④统一收口」三原则机械统一为同一出口形状：
 * 任何步骤的产出都收敛到 Proceed/ShortCircuit/Degrade/Retry，不可能出现数据结构不一致。
 */
public sealed interface StepOutcome permits StepOutcome.Proceed, StepOutcome.ShortCircuit, StepOutcome.Degrade, StepOutcome.Retry {

    /** 继续下一步。 */
    record Proceed() implements StepOutcome {
    }

    /** 话术短路：跳过后续所有步骤，返回 {@code scenario} 对应话术，零 LLM。 */
    record ShortCircuit(DegradationScenario scenario) implements StepOutcome {
    }

    /** 降级兜底：标记 {@code scenario} 但继续推进（不阻塞）。 */
    record Degrade(DegradationScenario scenario) implements StepOutcome {
    }

    /** 重试本步：图编排下自循环回本节点再执行（工具自纠正，§5.13）；线性编排下等价 Proceed。 */
    record Retry() implements StepOutcome {
    }
}
