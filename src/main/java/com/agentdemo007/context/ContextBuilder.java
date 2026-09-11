package com.agentdemo007.context;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文构建工厂入口（第五层·{@code @Order(700)}，紧随能力层 RAG/工具/HITL 步骤）。
 *
 * <p>委托 {@link ContextMerger} 按序拼接三层隔离结果（Sys→Runtime｜His→RAG→Tool｜User），
 * 写入 {@code context.assembledPrompt}，供 Phase 12 模型网关步骤消费。
 *
 * <p>降级语义（§5.12：上下文构建为内部步骤、不直接面向用户→无话术、不短路）：
 * 拼接异常时走行为级降级——兜底 [System(默认), User(sanitize(rawInput))] + Proceed，
 * 不标 degraded、不触发话术（②每步降级：程序始终运行）。
 */
@Component
@Order(700)
public class ContextBuilder implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final ContextMerger merger;
    private final PromptSanitizer sanitizer;

    public ContextBuilder(ContextMerger merger, PromptSanitizer sanitizer) {
        this.merger = merger;
        this.sanitizer = sanitizer;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        try {
            context.setAssembledPrompt(merger.merge(context));
            log.debug("上下文构建完成：sessionId={} segments={}",
                    context.sessionId(), context.assembledPrompt().size());
            return new StepOutcome.Proceed();
        } catch (Exception e) {
            log.warn("上下文拼接异常，行为级降级兜底：sessionId={} reason={}",
                    context.sessionId(), e.getMessage(), e); // 审计
            context.setAssembledPrompt(minimalFallback(context));
            return new StepOutcome.Proceed();
        }
    }

    /** 兜底最小上下文：默认系统提示词 + 包裹后的用户原始输入（保隔离）。 */
    private List<ChatMessage> minimalFallback(PipelineContext context) {
        return List.of(
                new ChatMessage.System(SystemAnchorLayer.DEFAULT_SYSTEM_PROMPT),
                new ChatMessage.User(sanitizer.sanitize(context.rawInput())));
    }
}
