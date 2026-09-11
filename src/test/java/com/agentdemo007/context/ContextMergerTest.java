package com.agentdemo007.context;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.prompt.LocalPromptSource;
import com.agentdemo007.prompt.PromptRegistry;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文按序拼接器测试（第五层·ContextMerger）。
 *
 * <p>注入三层构建器并强制 §5.5 固定顺序：Sys→Runtime｜His→RAG→Tool｜User。
 * 本测试用真实的三个层（非 mock），验证真实组合下的端到端拼接顺序与隔离结构。
 */
class ContextMergerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T03:00:00Z"), ZoneOffset.ofHours(8));
    private final PromptRegistry registry = new LocalPromptSource();
    private final PromptSanitizer sanitizer = new PromptSanitizer();

    private ContextMerger newMerger() {
        return new ContextMerger(
                new SystemAnchorLayer(registry, FIXED_CLOCK),
                new ObjectiveDataLayer(),
                new UserInstructionLayer(sanitizer));
    }

    @Test
    void emptyContext_stillProducesSystemAndUser() {
        ContextMerger merger = newMerger();
        PipelineContext ctx = new PipelineContext("s", "你好");

        List<ChatMessage> msgs = merger.merge(ctx);

        // 锚点 System(1) + 客观数据空 + 用户 User(1) = 2
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(msgs.get(1)).isInstanceOf(ChatMessage.User.class);
        assertThat(msgs.get(1).content()).contains("你好");
    }

    @Test
    void allSegments_orderedSystemHistoryRagToolUser() {
        ContextMerger merger = newMerger();
        PipelineContext ctx = new PipelineContext("s", "分析");
        ctx.setSummary("会话主题");
        ctx.setIntent(Intent.REASONING);
        ctx.setHistory(List.of(
                new ChatMessage.User("h1"),
                new ChatMessage.Ai("a1")));
        ctx.setRagFragments(List.of("ragF"));
        ctx.setToolResults(List.of("toolR"));

        List<ChatMessage> msgs = merger.merge(ctx);

        // Sys(1) + His(2) + RAG(1) + Tool(1) + User(1) = 6，且顺序固定
        assertThat(msgs).hasSize(6);
        assertThat(msgs.get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(msgs.get(1)).isInstanceOf(ChatMessage.User.class);   // 历史 h1
        assertThat(msgs.get(1).content()).isEqualTo("h1");
        assertThat(msgs.get(2)).isInstanceOf(ChatMessage.Ai.class);      // 历史 a1
        assertThat(msgs.get(3)).isInstanceOf(ChatMessage.User.class);   // RAG 框定
        assertThat(msgs.get(3).content()).contains("ragF");
        assertThat(msgs.get(4)).isInstanceOf(ChatMessage.ToolResult.class);
        assertThat(msgs.get(4).content()).contains("toolR");
        assertThat(msgs.get(5)).isInstanceOf(ChatMessage.User.class);   // 用户指令
        assertThat(msgs.get(5).content()).contains("分析");
    }

    @Test
    void onlyHistory_objectivePlacedBetweenSystemAndUser() {
        ContextMerger merger = newMerger();
        PipelineContext ctx = new PipelineContext("s", "继续");
        ctx.setHistory(List.of(new ChatMessage.Ai("prev")));

        List<ChatMessage> msgs = merger.merge(ctx);

        // Sys(1) + His(1) + User(1) = 3
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(msgs.get(1)).isInstanceOf(ChatMessage.Ai.class);
        assertThat(msgs.get(2)).isInstanceOf(ChatMessage.User.class);
    }
}
