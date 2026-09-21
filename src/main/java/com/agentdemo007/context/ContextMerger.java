package com.agentdemo007.context;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文按序拼接器（第五层·ContextMerger）。
 *
 * <p>注入各层构建器并强制 §5.5 固定拼接顺序（Phase 22 T102 设计修订：画像撤出 System 锚点，
 * 经 {@link UserMemoryLayer} 独立消息块注入，2026-09-20 用户裁决）：
 * <pre>
 *   系统锚点层(Sys→Runtime) → 记忆参考块(画像) → 客观数据层(His→RAG→Tool) → 用户指令层(User)
 * </pre>
 * 层数据不串层污染：系统指令、长期记忆参考、客观检索/历史数据、用户输入各自隔离，
 * 经本类串联为唯一 {@code List<ChatMessage>}，作为 {@link PipelineContext#assembledPrompt} 的真相源。
 */
public class ContextMerger {

    private final SystemAnchorLayer systemAnchorLayer;
    private final UserMemoryLayer userMemoryLayer;
    private final ObjectiveDataLayer objectiveDataLayer;
    private final UserInstructionLayer userInstructionLayer;

    public ContextMerger(SystemAnchorLayer systemAnchorLayer,
                         UserMemoryLayer userMemoryLayer,
                         ObjectiveDataLayer objectiveDataLayer,
                         UserInstructionLayer userInstructionLayer) {
        this.systemAnchorLayer = systemAnchorLayer;
        this.userMemoryLayer = userMemoryLayer;
        this.objectiveDataLayer = objectiveDataLayer;
        this.userInstructionLayer = userInstructionLayer;
    }

    /**
     * 按序拼接各层构建结果：Sys→Runtime｜记忆参考｜His→RAG→Tool｜User。
     *
     * @return 贯穿各层的完整上下文消息列表
     */
    public List<ChatMessage> merge(PipelineContext ctx) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.addAll(systemAnchorLayer.build(ctx));   // Sys→Runtime
        msgs.addAll(userMemoryLayer.build(ctx));     // 记忆参考块（画像缺位=空）
        msgs.addAll(objectiveDataLayer.build(ctx));  // His→RAG→Tool
        msgs.addAll(userInstructionLayer.build(ctx)); // User
        return msgs;
    }
}
