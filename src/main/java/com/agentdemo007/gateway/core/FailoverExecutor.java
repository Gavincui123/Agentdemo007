package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.observability.AgentTracer;
import com.agentdemo007.resilience.BackoffStrategy;
import com.agentdemo007.resilience.Decision;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ResilientExecutor;
import com.agentdemo007.resilience.RetryPolicy;
import com.agentdemo007.resilience.Sleeper;
import io.opentelemetry.api.trace.SpanKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 故障转移执行器（第六层·主模型失败后按 {@link FailoverPolicy} 切换备选）。
 *
 * <p>候选序列 = [主模型] + {@code fallbackModelIds}；逐个候选尝试，每个候选的执行经
 * {@link ResilientExecutor} 包裹做<b>同模型</b>退避重试（瞬态可重试异常）；
 * 同模型重试耗尽或不可重试客户端错 → 切下一个候选（故障转移=换模型，与重试正交）；
 * 全候选失败 → 抛 {@link LlmUnavailableException}（保留末次 cause）。每次转移事件写日志（审计 + 指标埋点占位）。
 *
 * <p>传播规则（§5.8）：{@link com.agentdemo007.resilience.FatalException}（致命：注入/权限）
 * 与 {@link com.agentdemo007.resilience.ToolRecoverableException}（工具可恢复）不故障转移——
 * 前者由上层审计拦截、绝不提交 LLM，后者由 ToolErrorFeedback 反馈 LLM 自纠正。
 * 其余（瞬态耗尽 / 不可重试客户端错）→ 切备选。
 *
 * <p>无参构造供 dev/未装配场景：内部以 noRetry 策略 + 默认分诊委托，行为等价"不重试、遇非致命异常即切备选"，
 * 保持与既有调用方（{@code new FailoverExecutor()}）二进制兼容。
 */
public class FailoverExecutor {

    private static final Logger log = LoggerFactory.getLogger(FailoverExecutor.class);

    /** 无重试路径用的空睡眠器（noRetry 永不睡眠）。 */
    private static final Sleeper NOOP_SLEEPER = millis -> {};

    private final ResilientExecutor resilientExecutor;
    private final RetryPolicy retryPolicy;
    private final ExceptionTriage triage;
    private final AgentMetrics metrics;
    private final AgentTracer tracer;

    /** dev/未装配：noRetry + 默认分诊，行为等价"不重试、非致命即切备选"。 */
    public FailoverExecutor() {
        this(new ResilientExecutor(new ExceptionTriage(), new BackoffStrategy(), NOOP_SLEEPER, new Random()),
                RetryPolicy.noRetry(), new ExceptionTriage());
    }

    /** 装配路径：注入重试模板、重试策略、分诊器（指标降级为 NO_OP，保持既有调用方不变）。 */
    public FailoverExecutor(ResilientExecutor resilientExecutor, RetryPolicy retryPolicy, ExceptionTriage triage) {
        this(resilientExecutor, retryPolicy, triage, AgentMetrics.NO_OP);
    }

    /** 装配路径（含指标）：注入重试模板、重试策略、分诊器、指标门面。 */
    public FailoverExecutor(ResilientExecutor resilientExecutor, RetryPolicy retryPolicy, ExceptionTriage triage,
                            AgentMetrics metrics) {
        this(resilientExecutor, retryPolicy, triage, metrics, AgentTracer.NO_OP);
    }

    /** 装配路径（Phase 23 追加 tracer）：链路门面随指标一并注入（GatewayConfig @Bean），gen_ai span 在此收口。 */
    public FailoverExecutor(ResilientExecutor resilientExecutor, RetryPolicy retryPolicy, ExceptionTriage triage,
                            AgentMetrics metrics, AgentTracer tracer) {
        this.resilientExecutor = resilientExecutor;
        this.retryPolicy = retryPolicy;
        this.triage = triage;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    public LlmResponse execute(GatewayRequest request, ModelExecutor executor) {
        FailoverPolicy policy = request.failoverPolicy();
        List<String> candidates = new ArrayList<>();
        candidates.add(request.primaryModelId());
        if (policy != null && policy.fallbackModelIds() != null) {
            candidates.addAll(policy.fallbackModelIds());
        }
        int maxAttempts = (policy == null ? 1 : policy.maxRetries() + 1);
        if (maxAttempts < 1) maxAttempts = 1;
        // 逐候选各试一次：同模型重试已由 ResilientExecutor 负责，故障转移不再循环重复同模型
        maxAttempts = Math.min(maxAttempts, candidates.size());

        Throwable lastCause = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String modelId = candidates.get(attempt);
            long start = System.nanoTime();
            // Phase 23 gen_ai span（CLIENT，对齐 OTel 生成式 AI 语义约定）：与「LLM出站」日志行同点位
            // 同口径——scene/model/attempt 落属性、成功落 token 用量、失败落 error 状态；Jaeger 里同轮
            // 多次出站（改写/意图/路由/终答/工具循环各一次）按 scene 可辨，与日志收口互为镜像。
            // gen_ai.system=openai：主备均为 OpenAI 兼容线协议（SiliconFlow/DashScope compatible-mode）。
            AgentTracer.SpanHandle llmSpan = tracer.startSpan("gen_ai.chat", SpanKind.CLIENT,
                    "gen_ai.system", "openai",
                    "gen_ai.request.model", modelId,
                    "agent.scene", request.sceneOrDefault(),
                    "agent.attempt", String.valueOf(attempt + 1),
                    "agent.failover.primary", request.primaryModelId());
            try {
                LlmResponse response = resilientExecutor.execute(
                        () -> executor.execute(new LlmRequest(modelId, request.prompt(), request.maxTokens(),
                                request.disableThinking(), request.messages(), request.tools())),
                        retryPolicy);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                metrics.recordModelCall(millis, true);
                llmSpan.attr("gen_ai.usage.total_tokens", response.tokens());
                llmSpan.attr("agent.response.model", response.modelId()); // 实际产出模型（转移时=备选）
                // [[q2-llm-egress-timing]] 日志埋点：人读日志打一行（scene + modelId + 耗时 + 成败 + attempt），
                // 供 tail 日志按调用定位各段延迟与用途归属（scene=查询改写/意图识别/路由计划/会话摘要/
                // 回答生成/工具调用——同轮多次小模型出站据此可辨，不再同形不可分）。millis 与
                // metrics.recordModelCall 同源（含同模型退避睡眠，单 HTTP 调用场景即该调用墙钟）。
                // 覆盖全部 LLM 出站（chat/chatRaw/decide + 工具派发经 GatewayChatModel.doChat→
                // gateway.invoke→本方法），一处收口不散落。
                log.info("LLM出站 scene={} model={} durMs={} ok=true attempt={}/{}",
                        request.sceneOrDefault(), modelId, millis, attempt + 1, maxAttempts);
                if (attempt > 0) {
                    log.warn("故障转移成功：scene={} 主={} 备选={} 尝试={}",
                            request.sceneOrDefault(), request.primaryModelId(), modelId, attempt + 1);
                    metrics.recordFailover(false); // 成功转移（非候选耗尽）
                }
                return response;
            } catch (RuntimeException e) {
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                metrics.recordModelCall(millis, false);
                llmSpan.error(e);
                llmSpan.attr("agent.attempt.durMs", millis);
                log.info("LLM出站 scene={} model={} durMs={} ok=false attempt={}/{} reason={}",
                        request.sceneOrDefault(), modelId, millis, attempt + 1, maxAttempts, e.getMessage());
                Decision d = triage.triage(e).decision();
                if (d == Decision.AUDIT_AND_FAIL || d == Decision.FEEDBACK_TO_LLM) {
                    throw e; // 致命/工具错：不故障转移，向上传播
                }
                lastCause = e;
                log.warn("模型执行失败，尝试备选：scene={} model={} attempt={}/{} reason={}",
                        request.sceneOrDefault(), modelId, attempt + 1, maxAttempts, e.getMessage());
            } finally {
                llmSpan.end(); // 每次尝试一个 span（重试/转移各成一段，瀑布图按 attempt 平铺可见）
            }
        }
        metrics.recordFailover(true); // 全候选耗尽
        throw new LlmUnavailableException("全部模型不可用：主=" + request.primaryModelId()
                + " 尝试=" + maxAttempts, lastCause);
    }
}
