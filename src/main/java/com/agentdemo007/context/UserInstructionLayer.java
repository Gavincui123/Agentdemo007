package com.agentdemo007.context;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.ChatMessage;

import java.util.List;

/**
 * 用户指令层（第五层·三层隔离之一）。
 *
 * <p>产出单条 {@link ChatMessage.User}：<b>恒取用户原话（{@code rawInput}）</b>——
 * 2026-09-17 用户定案：回答生成 LLM 必须看到用户原话，改写产物（{@code standardQuery}）
 * <b>只供 RAG 检索</b>（RagStep/工具内政策检索，为保证召回）不得进入回答 prompt，
 * 避免改写漂移把"用户没说的话"当成用户说的。原话经 {@link PromptSanitizer} 包裹定界符——
 * 用户指令隔离在数据区、无法逃逸成系统指令（§5.5 隔离 / §5.3.1 注入防御）。
 */
public class UserInstructionLayer {

    private final PromptSanitizer sanitizer;

    public UserInstructionLayer(PromptSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * 构建用户指令消息：用户原话经包裹后产出 User 消息（改写产物不入回答层，见类注定案）。
     *
     * @return 仅含一条已包裹的 {@link ChatMessage.User}
     */
    public List<ChatMessage> build(PipelineContext ctx) {
        return List.of(new ChatMessage.User(sanitizer.sanitize(ctx.rawInput())));
    }
}
