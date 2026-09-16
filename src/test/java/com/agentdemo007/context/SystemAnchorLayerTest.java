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
 * <p>系统提示词经 {@link SystemPromptAssembler} 装配（PromptRegistry→DEFAULT，②每步降级）
 * + 运行时元数据（会话摘要/意图/时间，Clock 可注入保证确定性）。Sys 与 Runtime 同属锚点层，
 * 折叠进同一条 System 消息（系统提示词在前、运行时块在后，保序）。
 */
class SystemAnchorLayerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T03:00:00Z"), ZoneOffset.ofHours(8));

    private final PromptRegistry emptyRegistry = new LocalPromptSource();

    /** 用 registry 构造层（assembler 取模板 miss→②降级 DEFAULT）。 */
    private SystemAnchorLayer layer(PromptRegistry registry) {
        return new SystemAnchorLayer(
                new SystemPromptAssembler(registry, SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT),
                FIXED_CLOCK);
    }

    @Test
    void emptyRegistry_usesDefaultSystemPrompt() {
        SystemAnchorLayer l = layer(emptyRegistry);
        PipelineContext ctx = new PipelineContext("s", "你好");

        List<ChatMessage> msgs = l.build(ctx);

        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0)).isInstanceOf(ChatMessage.System.class);
        assertThat(msgs.get(0).content()).startsWith(SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
    }

    @Test
    void segmentsTemplate_overridesDefault() {
        // system-anchor 已弃用；改用 system-prompt-segments 模板（bare YAML 数组，含一个 scene=all 段）
        LocalPromptSource registry = new LocalPromptSource();
        registry.put(SystemPromptAssembler.SEGMENTS_PROMPT_KEY,
                new PromptTemplate(SystemPromptAssembler.SEGMENTS_PROMPT_KEY, "1",
                        "- id: role\n  prompt: \"你是专属财务助手，回答需严谨。\"\n  scene: all\n  sort: 100\n  enabled: true\n",
                        "md5"));
        SystemAnchorLayer l = layer(registry);
        PipelineContext ctx = new PipelineContext("s", "你好");

        List<ChatMessage> msgs = l.build(ctx);

        assertThat(msgs.get(0).content()).startsWith("你是专属财务助手");
    }

    @Test
    void runtimeBlock_includesSummaryAndIntentAndTime() {
        SystemAnchorLayer l = layer(emptyRegistry);
        PipelineContext ctx = new PipelineContext("s", "分析 Q3");
        ctx.setSummary("会话主题：Q3 销售环比");
        ctx.setIntent(Intent.REASONING);

        String content = l.build(ctx).get(0).content();

        assertThat(content).startsWith(SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
        assertThat(content).contains("会话主题：Q3 销售环比");
        assertThat(content).contains(Intent.REASONING.description());
        assertThat(content).contains("多轮规则"); // 话题跟随规则恒在（新话题优先，防过度纠缠上文）
        assertThat(content).contains("2026-09-04"); // 运行时时间
    }

    @Test
    void nullSummaryAndIntent_runtimeBlockHasTimeOnly() {
        SystemAnchorLayer l = layer(emptyRegistry);
        PipelineContext ctx = new PipelineContext("s", "你好");

        String content = l.build(ctx).get(0).content();

        assertThat(content).contains("2026-09-04"); // 时间恒在
        assertThat(content).doesNotContain("会话摘要");
        assertThat(content).doesNotContain("当前意图");
    }
}
