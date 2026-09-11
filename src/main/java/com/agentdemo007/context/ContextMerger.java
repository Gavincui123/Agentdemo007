package com.agentdemo007.context;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文按序拼接器（第五层·ContextMerger）。
 *
 * <p>注入三层构建器并强制 §5.5 固定拼接顺序：
 * <pre>
 *   系统锚点层(Sys→Runtime) → 客观数据层(His→RAG→Tool) → 用户指令层(User)
 * </pre>
 * 三层数据不串层污染：系统指令、客观检索/历史数据、用户输入各自隔离，
 * 经本类串联为唯一 {@code List<ChatMessage>}，作为 {@link PipelineContext#assembledPrompt} 的真相源。
 */
public class ContextMerger {

    private final SystemAnchorLayer systemAnchorLayer;
    private final ObjectiveDataLayer objectiveDataLayer;
    private final UserInstructionLayer userInstructionLayer;

    public ContextMerger(SystemAnchorLayer systemAnchorLayer,
                         ObjectiveDataLayer objectiveDataLayer,
                         UserInstructionLayer userInstructionLayer) {
        this.systemAnchorLayer = systemAnchorLayer;
        this.objectiveDataLayer = objectiveDataLayer;
        this.userInstructionLayer = userInstructionLayer;
    }

    /**
     * 按序拼接三层构建结果：Sys→Runtime｜His→RAG→Tool｜User。
     *
     * @return 贯穿三层的完整上下文消息列表
     */
    public List<ChatMessage> merge(PipelineContext ctx) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.addAll(systemAnchorLayer.build(ctx));   // Sys→Runtime
        msgs.addAll(objectiveDataLayer.build(ctx));  // His→RAG→Tool
        msgs.addAll(userInstructionLayer.build(ctx)); // User
        return msgs;
    }
}
