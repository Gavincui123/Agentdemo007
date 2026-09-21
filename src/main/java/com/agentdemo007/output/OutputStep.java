package com.agentdemo007.output;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.ConcurrentReply;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.common.progress.ProgressEvent;
import com.agentdemo007.common.progress.ProgressEmitter;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /** 并发腿2 降级话术（子管线返 null/LLM 失败，§7.4）。 */
    private static final String LEG2_GRACEFUL_TEXT = "目前暂未找到您想要的商品，您可以浏览店内其他商品或告诉我更多偏好。";
    /** 并发腿1 await 超时（秒，§7.5）。 */
    private static final long LEG1_AWAIT_TIMEOUT_SECONDS = 30L;
    /** 流式 await 超时（秒）：等 LC4j doChat 异步回调完成再判退路（防误回退阻塞→双路并发）。 */
    private static final long STREAMING_AWAIT_SECONDS = 60L;

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
        // [[business-tools-workflow-dag]] §2.4 E1：presetReply 守卫——业务驳回（如订单非本人/超 7 天/不存在）
        // 时 WorkflowExecutionStep@670 已写 presetReply + Proceed，本步见非空即跳 LLM（chatRaw/网关/Schema 重试全跳），
        // securityFilter 脱敏后直写 finalReply 话术短路。业务驳回≠系统故障，不标记 degraded、不混降级语义。
        if (context.presetReply() != null) {
            context.setFinalReply(securityFilter.filter(context.presetReply()));
            log.debug("presetReply 话术短路，跳过 LLM：sessionId={}", context.sessionId());
            return new StepOutcome.Proceed();
        }
        // [[p0-intent-switch-clarify-design]] §7.5 并发合并分支（concurrentReply 非 null = 合并模式）
        if (context.concurrentReply() != null) {
            return mergeConcurrent(context);
        }
        List<ChatMessage> assembled = context.assembledPrompt();
        String prompt = flatten(assembled);
        // 【prompt 检查】提交 LLM 前打印 system prompt + 完整 flattened prompt——dev 检查拼接用；
        // Plan A 分段装配器落地后，此日志验片段按 scene/sort 拼接生效。仅 DEBUG（dev 可见，prod 关）。
        // 注：chatRaw 把整条 flattened 串当一个 prompt 提交（LLM 收到的是单串，system 段为其前缀，
        // 非独立 system-role 消息——见 chatRaw javadoc「prod 应改接收 List<ChatMessage> 结构化消息」）。
        if (log.isDebugEnabled() && !assembled.isEmpty() && assembled.get(0) instanceof ChatMessage.System sys) {
            log.debug("提交LLM的 system prompt（System 锚点层=systemPrompt+runtimeBlock）：\n{}", sys.content());
            log.debug("提交LLM的完整 prompt（{} 层 flattened，即 LLM 实际收到的单串）：\n{}", assembled.size(), prompt);
        }

        // [[q2-token-streaming]] 流式分支：emitter 非 NO_OP（/chat/stream）→ 逐 token 流式（经
        // chatRawStream→gateway.stream→executor.stream，主模型 only 无中途故障转移）。每 token 经
        // emitter 发 TokenChunk→reply_chunk SSE 实时 flush；onComplete 写 finalReply。流式跳 Schema 校验
        // （自由文本回复；结构化抽取仍走下面阻塞路径含 Schema/reAsk）；onError/异常→落下面阻塞 fallback
        // （chatRaw 主备容灾 + Schema 校验），韧性不丢。
        //
        // ⚠️ LC4j OpenAiStreamingChatModel.doChat 用 sendAsync（非阻塞），chatRawStream 返回时流式
        // 尚未完成——须 CountDownLatch 等 onComplete/onError 回调后再判退路，否则 holder 仍 null→
        // 误回退阻塞 chatRaw→两路 LLM 并发（SSE emitter 已 complete 后仍收流式 token→数百 "already
        // completed" 警告）。同步调用时 latch 已 countDown→await 即返回（无额外等待）。
        ProgressEmitter emitter = context.emitter();
        if (emitter != null && emitter != ProgressEmitter.NO_OP) {
            String[] finalReplyHolder = {null};
            int[] usageTokens = {0};
            boolean[] errored = {false};
            CountDownLatch latch = new CountDownLatch(1);
            StreamingReplyHandler handler = new StreamingReplyHandler() {
                @Override
                public void onPartialResponse(String token) {
                    emitter.emit(new ProgressEvent.TokenChunk(token));
                }
                @Override
                public void onCompleteResponse(String fullReply, int tokens) {
                    finalReplyHolder[0] = fullReply;
                    usageTokens[0] = tokens;
                    latch.countDown();
                }
                @Override
                public void onError(Throwable error) {
                    errored[0] = true;
                    log.warn("流式生成失败，回退阻塞 chatRaw：sessionId={} reason={}",
                            context.sessionId(), error.getMessage());
                    latch.countDown();
                }
            };
            try {
                llmService.chatRawStream(prompt, context.intent(), handler);
                if (!latch.await(STREAMING_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    errored[0] = true;
                    log.warn("流式超时（{}s），回退阻塞 chatRaw：sessionId={}",
                            STREAMING_AWAIT_SECONDS, context.sessionId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errored[0] = true;
            } catch (Exception e) {
                errored[0] = true;
                log.warn("流式异常，回退阻塞 chatRaw：sessionId={} reason={}",
                        context.sessionId(), e.getMessage());
            }
            if (!errored[0] && finalReplyHolder[0] != null) {
                context.setModelResponse(finalReplyHolder[0]);
                context.setLastUsageTokens(usageTokens[0]); // Phase 22 T99：真实 usage 触发轨
                context.setFinalReply(securityFilter.filter(finalReplyHolder[0]));
                log.debug("流式输出完成：sessionId={}", context.sessionId());
                return new StepOutcome.Proceed();
            }
            // errored → 落阻塞 fallback（chatRaw 主备容灾 + Schema 校验）
        }

        try {
            // Phase 22 T99：终答主模型改走 detailed 出站拿真实 usage（tokens>0 写 ctx 触发轨，
            // 0/缺失=引擎未回 usage → 不触发压缩，字符启发式窗口照常工作）
            LlmResponse response = llmService.chatRawDetailed(prompt, context.intent(), "回答生成");
            context.setModelResponse(response.content());
            context.setLastUsageTokens(response.tokens());

            OutputSchema schema = schemaResolver.resolve(context.intent());
            OutputResult result = gateway.process(response.content(), schema, reAsk);
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

    // ---- [[p0-intent-switch-clarify-design]] §7.5 并发合并 ----

    private StepOutcome mergeConcurrent(PipelineContext context) {
        ConcurrentReply cr = context.concurrentReply();
        String leg1 = awaitLeg1(cr.leg1Text());

        ProgressEmitter emitter = context.emitter();
        boolean streaming = emitter != null && emitter != ProgressEmitter.NO_OP;

        if (streaming) {
            // SSE 合并序：腿1 文本作为单个 TokenChunk 先发，再走腿2 流式逐 token
            emitter.emit(new ProgressEvent.TokenChunk(leg1 + "\n"));
        }

        String leg2 = produceLeg2(context, cr, streaming);
        context.setFinalReply(securityFilter.filter(leg1) + "\n" + securityFilter.filter(leg2));
        log.debug("并发合并完成：sessionId={}", context.sessionId());
        return new StepOutcome.Proceed();
    }

    private String awaitLeg1(Future<String> future) {
        try {
            return future.get(LEG1_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("并发腿1 await 超时/失败，优雅话术：{}", e.getMessage());
            return com.agentdemo007.capability.workflow.WorkflowExecutionStep.WORKFLOW_PENDING_TEXT;
        }
    }

    private String produceLeg2(PipelineContext context, ConcurrentReply cr, boolean streaming) {
        if (cr.leg2Prompt() == null || cr.leg2Prompt().isEmpty()) {
            return LEG2_GRACEFUL_TEXT; // 腿2 降级：子管线返 null/空
        }
        String prompt = flatten(cr.leg2Prompt());
        Intent cognitiveIntent = mapToCognitiveIntent(cr.leg2Intent());
        ProgressEmitter emitter = context.emitter();
        try {
            if (streaming) {
                String[] full = {null};
                boolean[] errored = {false};
                StreamingReplyHandler handler = new StreamingReplyHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        emitter.emit(new ProgressEvent.TokenChunk(token));
                    }
                    @Override
                    public void onCompleteResponse(String fullReply, int tokens) {
                        full[0] = fullReply;
                    }
                    @Override
                    public void onError(Throwable error) {
                        errored[0] = true;
                    }
                };
                llmService.chatRawStream(prompt, cognitiveIntent, handler);
                if (!errored[0] && full[0] != null) {
                    return full[0];
                }
                // streaming failed → fall through to blocking
            }
            return llmService.chatRaw(prompt, cognitiveIntent);
        } catch (Exception e) {
            log.warn("并发腿2 LLM 失败，优雅话术：sessionId={} reason={}", context.sessionId(), e.getMessage());
            return LEG2_GRACEFUL_TEXT;
        }
    }

    /** 业务意图 → 认知意图（并发腿2 LLM 调用用；非闲聊业务查询→REASONING，null/未知→OTHER）。 */
    private static Intent mapToCognitiveIntent(String businessIntent) {
        return (businessIntent != null) ? Intent.REASONING : Intent.OTHER;
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
