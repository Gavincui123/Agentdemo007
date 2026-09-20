package com.agentdemo007.context;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客观数据层拒答配套单测（[[refusal-design]]）：「工具无数据」块按隔离头注入、
 * 空通道跳过；SystemAnchorLayer 在 groundingMiss 时注入 Runtime 拒答约束。
 */
class ObjectiveDataLayerRefusalTest {

    private final ObjectiveDataLayer layer = new ObjectiveDataLayer();

    @Test
    void toolDataMissesRenderedWithHonestyHeader() {
        PipelineContext ctx = new PipelineContext("s1", "ORD-999 到哪了");
        ctx.setToolDataMisses(List.of("订单 ORD-999 不存在"));

        List<ChatMessage> msgs = layer.build(ctx);

        assertThat(msgs).hasSize(1);
        String content = msgs.get(0).content();
        assertThat(content).startsWith("【工具无数据】");
        assertThat(content).contains("必须如实告知用户未查询到对应记录");
        assertThat(content).contains("订单 ORD-999 不存在");
    }

    @Test
    void emptyMissChannelSkipped() {
        PipelineContext ctx = new PipelineContext("s1", "你好");
        assertThat(layer.build(ctx)).isEmpty();
    }

    @Test
    void systemAnchorInjectsRefusalConstraintWhenGroundingMiss() {
        com.agentdemo007.context.SystemPromptAssembler assembler =
                new com.agentdemo007.context.SystemPromptAssembler(new com.agentdemo007.prompt.LocalPromptSource(),
                        SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT);
        SystemAnchorLayer anchor = new SystemAnchorLayer(assembler, java.time.Clock.systemUTC());

        PipelineContext miss = new PipelineContext("s1", "退款政策是什么");
        miss.setGroundingMiss(true);
        String missContent = anchor.build(miss).get(0).content();
        assertThat(missContent).contains("拒答约束:").contains("严禁依据自身知识补答");

        PipelineContext hit = new PipelineContext("s1", "退款政策是什么");
        String hitContent = anchor.build(hit).get(0).content();
        assertThat(hitContent).doesNotContain("拒答约束:");
    }
}
