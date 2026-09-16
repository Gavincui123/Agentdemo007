package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.session.model.ChatMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子管线实现：直接驱动能力段三步骤（引擎无关——三步为两种编排模式共用的 @Component）。
 *
 * <p>跑 {@link PipelineStep} 三步（Tool@650 → RAG@660 → ContextBuilder@700），返 assembledPrompt；
 * 任一步异常→null（腿2 降级，{@code OutputStep} 优雅兜底，§7.4）。
 *
 * <p>构造器取 {@link PipelineStep}（非具体 ToolExecutionStep/RagStep/ContextBuilder）——
 * 测试可传假 step（lambda）验顺序与异常隔离；Spring DI 经 {@code @Qualifier} 按名注入真 bean
 * （{@code toolExecutionStep} / {@code ragStep} / {@code contextBuilder}）。
 */
@Component
public class CapabilitySegmentRunner implements SubPipelineRunner {

    private final PipelineStep toolStep;
    private final PipelineStep ragStep;
    private final PipelineStep contextBuilder;

    public CapabilitySegmentRunner(@Qualifier("toolExecutionStep") PipelineStep toolStep,
                                   @Qualifier("ragStep") PipelineStep ragStep,
                                   @Qualifier("contextBuilder") PipelineStep contextBuilder) {
        this.toolStep = toolStep;
        this.ragStep = ragStep;
        this.contextBuilder = contextBuilder;
    }

    @Override
    public List<ChatMessage> runCapabilitySegment(PipelineContext sub) {
        try {
            toolStep.process(sub);      // ShortCircuit 返回值被忽略（=该步降级，继续组装）
            ragStep.process(sub);
            contextBuilder.process(sub);
            return sub.assembledPrompt();
        } catch (Exception e) {
            return null;                // 腿2 失败 → OutputStep 优雅兜底（§7.4）
        }
    }
}
