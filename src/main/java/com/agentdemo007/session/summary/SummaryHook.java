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

    /**
     * 滚动摘要增量合并（Phase 22·T100/T101，终局异步压缩路径专用）：
     * 旧摘要 + 新滑出轮次 → 合并后的新摘要（≤200 字契约，垃圾守护继承）。
     *
     * <p>返回 empty 表示本次合并不可用（LLM 失败/垃圾输出）——调用方沿用旧摘要继续
     * （业务键白名单由调用方独立补齐，不依赖摘要质量），永不阻塞、永不重压全量。
     *
     * @param oldSummary 既有滚动摘要（可为 null=尚无）
     * @param slidOut    本次滑出窗口的轮次消息（User/Ai 对）
     */
    default Optional<String> summarizeRolling(String oldSummary, List<ChatMessage> slidOut) {
        return Optional.empty();
    }
}
