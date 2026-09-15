package com.agentdemo007.common.pipeline;

import com.agentdemo007.session.model.ChatMessage;

import java.util.List;
import java.util.concurrent.Future;

/**
 * 并发合并产物载体（[[p0-intent-switch-clarify-design]] §7）：腿1 工作流图异步 Future + 腿2 组装 prompt + 腿2 意图。
 *
 * <p>由 {@code WorkflowExecutionStep} 的并发分支（Task 14）填充，{@code OutputStep} 合并分支（Task 16）消费：
 * <ul>
 *   <li>{@code leg1Text}——售后工作流图异步跑出的确认话术 Future（腿1 降级→客服话术兜底）；</li>
 *   <li>{@code leg2Prompt}——子管线跑出的 assembledPrompt（腿2 降级→null，OutputStep 优雅兜底）；</li>
 *   <li>{@code leg2Intent}——腿2 的业务意图（如 {@code product_query}），供 OutputStep 映射为认知意图调 LLM。</li>
 * </ul>
 */
public record ConcurrentReply(Future<String> leg1Text, List<ChatMessage> leg2Prompt, String leg2Intent) {
}
