package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.BackoffStrategy;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.FatalException;
import com.agentdemo007.resilience.NonRetryableException;
import com.agentdemo007.resilience.ResilientExecutor;
import com.agentdemo007.resilience.RetryPolicy;
import com.agentdemo007.resilience.Sleeper;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.resilience.ToolHttpException;
import com.agentdemo007.resilience.ToolTimeoutException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LC4j 工具执行的韧性装饰器（Phase 9 工具韧性·2026-09-17 扩展：重试/超时/分类收口）。
 *
 * <p>包 LC4j {@code DefaultToolExecutor}（执行原语：JSON 解析 + 参数强转 + 反射调 {@code @Tool}），
 * 在其上挂完整韧性栈——<b>执行原语归 LC4j，韧性归项目</b>（[[dont-hardwrite-use-dep-methods]]：
 * 库方法优先；[[langchain4j-boot4-compat-findings]]：seam=委托非重写）：
 * <ul>
 *   <li>per-tool 熔断（{@link ToolCircuitBreaker}）：入口 allow-check（OPEN 快速失败抛
 *       {@link ToolCircuitOpenException} 交 {@code ToolExecutionStep} 收口 {@code TOOL_FAILURE}）+
 *       终局记账；</li>
 *   <li>分诊重试（{@link ResilientExecutor} + {@link ToolExceptionTriage}）：瞬态失败
 *       （超时/5xx/未知异常）按 {@link RetryPolicy} 指数退避 + 全抖动重试；参数非法/4xx 明确拒绝
 *       不重试（重试同参数无意义）；</li>
 *   <li>per-call 超时（{@code app.tool.timeout-ms}）：{@link FutureTask} + 守护线程池硬中断
 *       （{@code cancel(true)}），防外部系统 hang 死拖垮流水线；0=关闭；</li>
 *   <li>失败收口：<b>不抛、不吞</b>——分类为 {@link ToolErrorKind}，产出结构化错误 JSON
 *       （{@link ToolError#toText}）随 {@link ToolInvocation} 返回，交 {@code ToolCallExecutor}
 *       Agent loop 回喂探测 LLM 自纠正/澄清、终答 LLM 如实向客户说明。</li>
 * </ul>
 *
 * <p>熔断记账口径（按 invoke 终局计，非按 attempt 计）：成功 → {@code recordSuccess}；
 * 失败且归因工具/外部系统健康（超时/5xx/未知运行时异常）→ {@code recordFailure} 一次；
 * 参数非法（{@link ToolArgumentsException}，模型侧问题）/ 幻觉工具名 / 4xx 明确拒绝
 * （外部系统有应答=服务健康）/ {@link NonRetryableException} / {@link FatalException} 不记——
 * 避免模型输错参数或外部系统拒绝脏请求把健康工具打熔断。
 */
public class ResilientToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientToolExecutor.class);

    /** 超时硬中断用共享守护线程池（daemon 不阻 JVM 退出；demo 规模共享足够，per-tool 故障隔离归熔断器）。 */
    private static final ExecutorService TIMEOUT_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-invocation-guard");
        t.setDaemon(true);
        return t;
    });

    /** 生产退避睡眠：被中断时恢复中断标志并以异常中止重试链（不吞中断）。 */
    private static final Sleeper THREAD_SLEEPER = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("工具退避睡眠被中断", e);
        }
    };

    private final ToolExecutor delegate;
    private final ToolCircuitBreaker breaker;
    private final ResilientExecutor resilient;
    private final RetryPolicy retryPolicy;
    private final long timeoutMs;

    /** 兼容构造（无重试/无超时）：既有 AiServices/单测装配。 */
    public ResilientToolExecutor(ToolExecutor delegate, ToolCircuitBreaker breaker) {
        this(delegate, breaker, RetryPolicy.noRetry(), 0L);
    }

    /** 生产构造：分诊重试（指数退避+全抖动）+ per-call 超时（0=关）。 */
    public ResilientToolExecutor(ToolExecutor delegate, ToolCircuitBreaker breaker,
                                 RetryPolicy retryPolicy, long timeoutMs) {
        this(delegate, breaker, retryPolicy, timeoutMs,
                new ResilientExecutor(new ToolExceptionTriage(), new BackoffStrategy(),
                        THREAD_SLEEPER, new Random()));
    }

    /** 全量构造（测试缝）：注入 {@link ResilientExecutor}（可注入 Sleeper/Random 验退避与抖动）。 */
    public ResilientToolExecutor(ToolExecutor delegate, ToolCircuitBreaker breaker,
                                 RetryPolicy retryPolicy, long timeoutMs, ResilientExecutor resilient) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.retryPolicy = (retryPolicy != null) ? retryPolicy : RetryPolicy.noRetry();
        this.timeoutMs = Math.max(timeoutMs, 0L);
        this.resilient = resilient;
    }

    /**
     * 项目级单工具调用入口（韧性收口）：熔断前置检查 → 分诊驱动重试 → 超时硬中断 →
     * 失败分类收口为结构化错误结果（不抛不吞，回喂 LLM）。
     *
     * @throws ToolCircuitOpenException breaker OPEN（交上层收口 TOOL_FAILURE，不产生错误结果）
     */
    public ToolInvocation invoke(ToolExecutionRequest request) {
        String name = request.name();
        if (!breaker.allow(name)) {
            // OPEN 快速失败：零工具执行，抛出由上层 ToolExecutionStep 收口 TOOL_FAILURE
            throw new ToolCircuitOpenException("工具熔断中：" + name);
        }
        AtomicInteger attempts = new AtomicInteger();
        try {
            String content = resilient.execute(() -> {
                attempts.incrementAndGet();
                return callOnce(request);
            }, retryPolicy);
            breaker.recordSuccess(name);
            return new ToolInvocation(content, null);
        } catch (ToolCircuitOpenException e) {
            throw e; // 防御透传：callOnce 不产生该类型，熔断开路必须上抛收口而非转错误结果
        } catch (RuntimeException e) {
            ToolError error = new ToolError(kindOf(e), rootMessage(e), attempts.get());
            boolean breakerWorthy = breakerWorthy(e);
            if (breakerWorthy) {
                breaker.recordFailure(name);
            }
            log.warn("工具调用失败收口为错误结果（回喂 LLM）：tool={} kind={} attempts={} breakerWorthy={} message={}",
                    name, error.kind(), error.attempts(), breakerWorthy, error.message()); // 审计
            return new ToolInvocation(error.toText(name), error);
        }
    }

    /** LC4j String 契约（兼容既有调用方）：错误结果同样以文本返回（isError 经 executeWithContext 保留）。 */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request,
                InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
    }

    /** LC4j 结果契约：失败结果带 {@code isError=true}（AiServices 原生路径可感知错误标记）。 */
    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        ToolInvocation inv = invoke(request);
        return ToolExecutionResult.builder()
                .resultText(inv.content())
                .isError(inv.isError())
                .build();
    }

    /** 单次执行（超时预算内）：timeoutMs>0 走守护线程池硬中断，否则直调。 */
    private String callOnce(ToolExecutionRequest request) {
        if (timeoutMs <= 0) {
            return delegate.execute(request, null);
        }
        FutureTask<String> task = new FutureTask<>(() -> delegate.execute(request, null));
        TIMEOUT_POOL.execute(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            task.cancel(true); // 硬中断底层执行线程（外部系统 hang 死防线）
            throw new ToolTimeoutException("工具执行超时（" + timeoutMs + "ms）：" + request.name());
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            throw (c instanceof RuntimeException re) ? re : new RuntimeException(c);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("工具调用等待被中断：" + request.name(), ie);
        }
    }

    /** 失败分类（ToolErrorKind 语义见类注释）：解包 LC4j 包装后按真实异常归因。 */
    private static ToolErrorKind kindOf(Throwable e) {
        if (e instanceof ToolArgumentsException) {
            return ToolErrorKind.PARAM_INVALID;
        }
        if (e instanceof ToolTimeoutException) {
            return ToolErrorKind.TIMEOUT;
        }
        if (e instanceof ToolHttpException h) {
            return (h.status() >= 500) ? ToolErrorKind.HTTP_5XX : ToolErrorKind.HTTP_4XX;
        }
        if (e instanceof ToolExecutionException && e.getCause() != null) {
            return kindOf(e.getCause()); // DefaultToolExecutor 包装：解包真实异常归因
        }
        return ToolErrorKind.TOOL_EXCEPTION;
    }

    /** 熔断记账判定：模型侧/调用侧问题与外部明确拒绝不记（工具健康），服务端劣化记。 */
    private static boolean breakerWorthy(Throwable e) {
        if (e instanceof ToolArgumentsException) {
            return false;
        }
        if (e instanceof ToolHttpException h) {
            return h.status() >= 500;
        }
        if (e instanceof ToolTimeoutException) {
            return true;
        }
        if (e instanceof ToolExecutionException && e.getCause() != null) {
            return breakerWorthy(e.getCause());
        }
        if (e instanceof NonRetryableException || e instanceof FatalException) {
            return false;
        }
        return true; // 未知运行时异常：乐观重试已做，仍失败按健康劣化记账（熔断兜底系统性故障）
    }

    /** 根因消息（LC4j 包装层解包；无 message 兜底异常类名，镜像 LC4j errorMessage 语义）。 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t instanceof ToolExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        return (t.getMessage() != null) ? t.getMessage() : t.getClass().getName();
    }
}
