package com.agentdemo007.context;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.StandardQuery;

import java.util.List;

/**
 * 用户指令层（第五层·三层隔离之一）。
 *
 * <p>产出单条 {@link ChatMessage.User}：取改写后的标准化 Query（{@code standardQuery}），
 * 缺失则回退 {@code rawInput}（②每步降级：不阻塞，始终拿到自足 Query），
 * 再经 {@link PromptSanitizer} 包裹定界符——使用户指令隔离在数据区、
 * 无法逃逸成系统指令（§5.5 隔离 / §5.3.1 注入防御）。
 */
public class UserInstructionLayer {

    private final PromptSanitizer sanitizer;

    public UserInstructionLayer(PromptSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * 构建用户指令消息：standardQuery 优先，缺失回退 rawInput，经包裹后产出 User 消息。
     *
     * @return 仅含一条已包裹的 {@link ChatMessage.User}
     */
    public List<ChatMessage> build(PipelineContext ctx) {
        String query = resolveQuery(ctx);
        return List.of(new ChatMessage.User(sanitizer.sanitize(query)));
    }

    /** 指令文本：改写后的标准化 Query 优先，缺失回退原始输入（②每步降级）。 */
    private String resolveQuery(PipelineContext ctx) {
        StandardQuery sq = ctx.standardQuery();
        return (sq != null) ? sq.text() : ctx.rawInput();
    }
}
