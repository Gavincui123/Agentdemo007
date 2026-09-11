package com.agentdemo007.session.summary;

import com.agentdemo007.session.model.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * 会话摘要 Hook（第二层·新建会话触发，生成上下文锚点）。
 *
 * <p>新建会话（无历史）时由 {@code SessionRouter} 触发，生成一句会话摘要/主题，
 * 作为后续上下文锚点写入 {@code PipelineContext.summary}（§5.2.1）。
 *
 * <p>返回 {@code Optional.empty()} 表示摘要不可用（模型故障/空输出）——调用方应跳过锚点继续推进，
 * 不阻塞链路（②每步降级）。
 */
public interface SummaryHook {

    /**
     * 生成会话摘要锚点。
     *
     * @param priorHistory  既有历史（新建会话为空）
     * @param currentInput  本轮用户输入
     * @return 摘要文本，或 empty 表示不可用
     */
    Optional<String> summarize(List<ChatMessage> priorHistory, String currentInput);
}
