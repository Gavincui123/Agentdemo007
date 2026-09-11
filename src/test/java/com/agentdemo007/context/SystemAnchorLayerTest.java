package com.agentdemo007.context;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.prompt.LocalPromptSource;
import com.agentdemo007.prompt.PromptRegistry;
import com.agentdemo007.prompt.PromptTemplate;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统锚点层测试（第五层·SystemAnchorLayer）。
 *
 * <p>系统锚点层产出单条 {@link ChatMessage.System}：系统提示词（PromptRegistry→内置默认，②每步降级）
 * + 运行时元数据（会话摘要/意图/时间，Clock 可注入保证确定性）。
 * Sys 与 Runtime 同属锚点层，折叠进同一条 System 消息（系统提示词在前、运行时块在后，保序）。
 */
class SystemAnchorLayerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T03:00:00Z"), ZoneOffset.ofHours(8));

    private final PromptRegistry emptyRegistry = new LocalPromptSource();

    @Test
    void emptyRegistry_usesDefaultSystemPrompt() {
        SystemAnchorLayer layer = new SystemAnchorLayer(emptyRegistry, FIXED_CLOCK);
        PipelineContext ctx = new PipelineContext("s", "你好");

        List<ChatMessage> msgs = layer.build(ctx);

        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(msgs.get(0).content()).startsWith(SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
    }

    @Test
    void registryProvided_overridesDefault() {
        LocalPromptSource registry = new LocalPromptSource();
        registry.put(SystemAnchorLayer.SYSTEM_PROMPT_KEY,
                new PromptTemplate("system-anchor", "1", "你是专属财务助手，回答需严谨。", "md5"));
        SystemAnchorLayer layer = new SystemAnchorLayer(registry, FIXED_CLOCK);
        PipelineContext ctx = new PipelineContext("s", "你好");

        List<ChatMessage> msgs = layer.build(ctx);

        assertThat(msgs.get(0).content()).startsWith("你是专属财务助手");
    }

    @Test
    void runtimeBlock_includesSummaryAndIntentAndTime() {
        SystemAnchorLayer layer = new SystemAnchorLayer(emptyRegistry, FIXED_CLOCK);
        PipelineContext ctx = new PipelineContext("s", "分析 Q3");
        ctx.setSummary("会话主题：Q3 销售环比");
        ctx.setIntent(Intent.REASONING);

        String content = layer.build(ctx).get(0).content();

        assertThat(content).startsWith(SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
        assertThat(content).contains("会话主题：Q3 销售环比");
        assertThat(content).contains(Intent.REASONING.description());
        assertThat(content).contains("2026-09-04"); // 运行时时间
    }

    @Test
    void nullSummaryAndIntent_runtimeBlockHasTimeOnly() {
        SystemAnchorLayer layer = new SystemAnchorLayer(emptyRegistry, FIXED_CLOCK);
        PipelineContext ctx = new PipelineContext("s", "你好");

        String content = layer.build(ctx).get(0).content();

        assertThat(content).contains("2026-09-04"); // 时间恒在
        assertThat(content).doesNotContain("会话摘要");
        assertThat(content).doesNotContain("当前意图");
    }
}
