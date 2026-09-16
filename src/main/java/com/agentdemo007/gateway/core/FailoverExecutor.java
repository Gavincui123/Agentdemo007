package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.BackoffStrategy;
import com.agentdemo007.resilience.Decision;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ResilientExecutor;
import com.agentdemo007.resilience.RetryPolicy;
import com.agentdemo007.resilience.Sleeper;
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
        this.resilientExecutor = resilientExecutor;
        this.retryPolicy = retryPolicy;
        this.triage = triage;
        this.metrics = metrics;
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
            try {
                LlmResponse response = resilientExecutor.execute(
                        () -> executor.execute(new LlmRequest(modelId, request.prompt(), request.maxTokens(),
                                request.disableThinking(), request.messages(), request.tools())),
                        retryPolicy);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                metrics.recordModelCall(millis, true);
                // [[q2-llm-egress-timing]] 日志埋点：人读日志打一行（modelId + 耗时 + 成败 + attempt），
                // 供 tail 日志按调用定位各段延迟。millis 与 metrics.recordModelCall 同源（含同模型退避睡眠，
                // 单 HTTP 调用场景即该调用墙钟）。覆盖全部 LLM 出站（chat/chatRaw/decide + 工具派发经
                // GatewayChatModel.doChat→gateway.invoke→本方法），一处收口不散落。
                log.info("LLM出站 model={} durMs={} ok=true attempt={}/{}", modelId, millis, attempt + 1, maxAttempts);
                if (attempt > 0) {
                    log.warn("故障转移成功：主={} 备选={} 尝试={}", request.primaryModelId(), modelId, attempt + 1);
                    metrics.recordFailover(false); // 成功转移（非候选耗尽）
                }
                return response;
            } catch (RuntimeException e) {
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                metrics.recordModelCall(millis, false);
                log.info("LLM出站 model={} durMs={} ok=false attempt={}/{} reason={}",
                        modelId, millis, attempt + 1, maxAttempts, e.getMessage());
                Decision d = triage.triage(e).decision();
                if (d == Decision.AUDIT_AND_FAIL || d == Decision.FEEDBACK_TO_LLM) {
                    throw e; // 致命/工具错：不故障转移，向上传播
                }
                lastCause = e;
                log.warn("模型执行失败，尝试备选：model={} attempt={}/{} reason={}",
                        modelId, attempt + 1, maxAttempts, e.getMessage());
            }
        }
        metrics.recordFailover(true); // 全候选耗尽
        throw new LlmUnavailableException("全部模型不可用：主=" + request.primaryModelId()
                + " 尝试=" + maxAttempts, lastCause);
    }
}
