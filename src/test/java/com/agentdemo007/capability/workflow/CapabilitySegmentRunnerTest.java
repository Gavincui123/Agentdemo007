package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CapabilitySegmentRunner} 单测（[[p0-intent-switch-clarify-design]] §7.3）。
 *
 * <p>用假 {@link PipelineStep} 验顺序（Tool→RAG→ContextBuilder）与异常隔离（任一步抛→null 降级），
 * 不依赖真实 Tool/RAG/ContextBuilder bean（纯逻辑验证，无 Spring 上下文）。
 */
class CapabilitySegmentRunnerTest {

    @Test
    void runCapabilitySegment_runsStepsInOrder_returnsAssembled() {
        PipelineStep tool = ctx -> { ctx.setToolResults(List.of("tool-out")); return new StepOutcome.Proceed(); };
        PipelineStep rag = ctx -> { ctx.setRagFragments(List.of("rag-out")); return new StepOutcome.Proceed(); };
        PipelineStep cb = ctx -> { ctx.setAssembledPrompt(List.of(new ChatMessage.System("assembled"))); return new StepOutcome.Proceed(); };
        CapabilitySegmentRunner r = new CapabilitySegmentRunner(tool, rag, cb);

        PipelineContext sub = new PipelineContext("s1", "买耳机");
        List<ChatMessage> out = r.runCapabilitySegment(sub);

        assertThat(out).hasSize(1);
        assertThat(sub.toolResults()).contains("tool-out");
        assertThat(sub.ragFragments()).contains("rag-out");
    }

    @Test
    void runCapabilitySegment_swallowsException_returnsNull() {
        PipelineStep tool = ctx -> { throw new IllegalStateException("tool fail"); };
        CapabilitySegmentRunner r = new CapabilitySegmentRunner(tool, ctx -> new StepOutcome.Proceed(), ctx -> new StepOutcome.Proceed());
        assertThat(r.runCapabilitySegment(new PipelineContext("s1", "x"))).isNull();
    }
}
