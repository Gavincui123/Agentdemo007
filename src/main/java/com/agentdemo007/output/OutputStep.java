package com.agentdemo007.output;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输出步骤（第七层·{@code @Order(800)}，紧随 {@code ContextBuilder(700)}）。
 *
 * <p>串起最终输出链路：组装 prompt → {@link ChatLlmService#chatRaw}（不二次包裹，已由
 * {@code ContextBuilder} 三层隔离）→ {@link StructuredOutputGateway}（结构校验 + 重试兜底）
 * → {@link OutputSecurityFilter}（脱敏 / 注入残留替换）→ 写 {@code context.finalReply}。
 *
 * <p>降级语义（§5.12 结构化输出行「兜底默认结构 + 话术兜底」、模型行「话术 + 短路」）：
 * <ul>
 *   <li>Schema 校验重试耗尽 → {@code Degrade(OUTPUT_FALLBACK)}：finalReply 置为话术、标记 degraded，
 *       继续推进（产出回复，不阻塞）；</li>
 *   <li>无可用模型 {@link ModelSelectionException} → {@code ShortCircuit(MODEL_DOWN)}（零 LLM，话术短路）；</li>
 *   <li>容灾耗尽 {@code LlmUnavailableException} → {@code ShortCircuit(FAILOVER_EXHAUSTED)}。</li>
 * </ul>
 * 模型原始响应先写 {@code context.modelResponse}（供结构化网关 / 审计消费），再经网关与安全过滤写 finalReply。
 */
@Component
@Order(800)
public class OutputStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(OutputStep.class);

    private final ChatLlmService llmService;
    private final StructuredOutputGateway gateway;
    private final OutputSecurityFilter securityFilter;
    private final OutputSchemaResolver schemaResolver;
    private final ReAsk reAsk;

    public OutputStep(ChatLlmService llmService, StructuredOutputGateway gateway, OutputSecurityFilter securityFilter,
                      OutputSchemaResolver schemaResolver, ReAsk reAsk) {
        this.llmService = llmService;
        this.gateway = gateway;
        this.securityFilter = securityFilter;
        this.schemaResolver = schemaResolver;
        this.reAsk = reAsk;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        try {
            String prompt = flatten(context.assembledPrompt());
            String rawOutput = llmService.chatRaw(prompt, context.intent());
            context.setModelResponse(rawOutput);

            OutputSchema schema = schemaResolver.resolve(context.intent());
            OutputResult result = gateway.process(rawOutput, schema, reAsk);
            String clean = securityFilter.filter(result.text());
            context.setFinalReply(clean);

            if (result.degraded()) {
                context.markDegraded(DegradationScenario.OUTPUT_FALLBACK);
                log.warn("结构化输出降级：sessionId={} reason=schema_exhausted", context.sessionId()); // 审计
                return new StepOutcome.Degrade(DegradationScenario.OUTPUT_FALLBACK);
            }
            log.debug("输出完成：sessionId={}", context.sessionId());
            return new StepOutcome.Proceed();
        } catch (LlmUnavailableException e) {
            log.warn("容灾耗尽，短路 FAILOVER_EXHAUSTED：sessionId={} reason={}", context.sessionId(), e.getMessage()); // 审计
            return new StepOutcome.ShortCircuit(DegradationScenario.FAILOVER_EXHAUSTED);
        } catch (ModelSelectionException e) {
            log.warn("无可用模型，短路 MODEL_DOWN：sessionId={} reason={}", context.sessionId(), e.getMessage()); // 审计
            return new StepOutcome.ShortCircuit(DegradationScenario.MODEL_DOWN);
        }
    }

    /**
     * 拼装 {@code List<ChatMessage>} 为 prompt 字符串（过渡薄层）。
     *
     * <p>prod LangChain4j 桥接应改为直传结构化消息列表（{@code chatRaw} 形状随之演进）；
     * dev 桩不消费 prompt 内容，简单按行拼接即可保持层级可读。
     */
    private String flatten(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return String.join("\n", messages.stream().map(ChatMessage::content).toList());
    }
}
