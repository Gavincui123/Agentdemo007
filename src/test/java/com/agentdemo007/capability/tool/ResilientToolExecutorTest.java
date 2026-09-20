package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.BackoffStrategy;
import com.agentdemo007.resilience.CircuitBreaker;
import com.agentdemo007.resilience.ExceptionCategory;
import com.agentdemo007.resilience.ResilientExecutor;
import com.agentdemo007.resilience.RetryPolicy;
import com.agentdemo007.resilience.Sleeper;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.resilience.ToolHttpException;
import com.agentdemo007.resilience.ToolTimeoutException;
import com.agentdemo007.resilience.TransientException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResilientToolExecutor} 测试——LC4j 执行原语上的韧性装饰（Phase 9 工具韧性·2026-09-17 扩展）。
 *
 * <p>覆盖面（对应"工具调用超时/参数错误/4xx/5xx 应对"验收）：
 * <ul>
 *   <li>成功 → 委托 + 记账不跳闸（兼容构造）；</li>
 *   <li>失败 → 分类收口为结构化错误结果（<b>不抛不吞</b>，回喂 LLM）+ 终局记熔断一次；</li>
 *   <li>OPEN → 快速失败抛 {@link ToolCircuitOpenException}，不调底层；</li>
 *   <li>瞬态（5xx/超时/未知）→ 指数退避 + 全抖动重试（{@link RecordingSleeper} 验退避落点）；</li>
 *   <li>4xx 明确拒绝 / 参数非法 → 不重试、不记熔断（外部系统有应答=健康/模型侧问题）；</li>
 *   <li>超时 → 守护线程硬中断转 {@link ToolTimeoutException} 可重试；</li>
 *   <li>真实 LC4j 原语（propagate/wrap 双开）：@Tool 异常经 {@code ToolExecutionException} 包装
 *       仍正确解包归因；坏 JSON 参数经 {@code ToolArgumentsException} → PARAM_INVALID。</li>
 * </ul>
 */
class ResilientToolExecutorTest {

    private static ToolExecutionRequest req(String name) {
        return ToolExecutionRequest.builder().name(name).arguments("{}").build();
    }

    /** 记录型睡眠（不真实睡眠）：捕获每次退避落点，验指数退避 + 抖动界。 */
    static final class RecordingSleeper implements Sleeper {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void sleepMs(long millis) {
            delays.add(millis);
        }
    }

    /** @Tool 固定桩：前 failTimes 次抛 ToolHttpException(status)，其后成功。 */
    static class HttpToolStub {
        final AtomicInteger calls = new AtomicInteger();
        final int failTimes;
        final int status;

        HttpToolStub(int failTimes, int status) {
            this.failTimes = failTimes;
            this.status = status;
        }

        @Tool("模拟外部系统 HTTP 调用")
        @SuppressWarnings("unused")
        String call() {
            if (calls.incrementAndGet() <= failTimes) {
                throw new ToolHttpException(status, "外部系统错误 HTTP " + status);
            }
            return "外部数据";
        }
    }

    /** propagating/wrap 双开的原语构造（镜像生产 ToolSchemaProvider 装配）。 */
    private static DefaultToolExecutor propagating(Object bean, Method method) {
        return new DefaultToolExecutor.Builder()
                .object(bean).originalMethod(method).methodToInvoke(method)
                .wrapToolArgumentsExceptions(Boolean.TRUE)
                .propagateToolExecutionExceptions(Boolean.TRUE)
                .build();
    }

    private static Method httpToolMethod() throws Exception {
        return HttpToolStub.class.getDeclaredMethod("call");
    }

    // ---- 兼容构造（无重试/无超时）----

    @Test
    void success_delegatesAndReturns_breakerStaysClosed() {
        ToolExecutor delegate = (r, m) -> "6";
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker);

        assertThat(exec.execute(req("triangleArea"), null)).isEqualTo("6");
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failure_classifiedAsErrorResult_breakerOpensAtThreshold_notRethrown() {
        // 2026-09-17 语义：失败不抛——分类收口为结构化错误结果回喂 LLM；终局记熔断一次
        ToolExecutor delegate = (r, m) -> { throw new RuntimeException("tool boom"); };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0); // 阈值1：一次失败即 OPEN
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker);

        ToolInvocation inv = exec.invoke(req("triangleArea"));

        assertThat(inv.isError()).isTrue();
        assertThat(inv.error().kind()).isEqualTo(ToolErrorKind.TOOL_EXCEPTION);
        assertThat(inv.error().attempts()).isEqualTo(1);
        assertThat(inv.error().message()).isEqualTo("tool boom");
        assertThat(inv.content()).contains("\"toolError\":true").contains("TOOL_EXCEPTION");
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openBreaker_shortCircuits_delegateNotCalled() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor delegate = (r, m) -> { calls.incrementAndGet(); return "x"; };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        breaker.recordFailure("triangleArea"); // 预先 OPEN
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec.execute(req("triangleArea"), null))
                .isInstanceOf(ToolCircuitOpenException.class);
        assertThat(calls.get()).isZero(); // 熔断 OPEN → 不调底层 delegate
    }

    // ---- 分诊重试（指数退避 + 全抖动）----

    @Test
    void transientFailure_retriedWithBackoff_thenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor delegate = (r, m) -> {
            if (calls.incrementAndGet() <= 1) {
                throw new TransientException("网络抖动");
            }
            return "ok";
        };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(3, 30000, () -> 0);
        RecordingSleeper sleeper = new RecordingSleeper();
        // maxAttempts=3, 100ms 起 ×2 封顶 10s，全抖动；Random 定种子可复现
        ResilientExecutor resilient = new ResilientExecutor(new ToolExceptionTriage(),
                new BackoffStrategy(), sleeper, new Random(42));
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker,
                new RetryPolicy(3, 100, 2.0, 10_000, true), 0L, resilient);

        ToolInvocation inv = exec.invoke(req("triangleArea"));

        assertThat(inv.isError()).isFalse();
        assertThat(inv.content()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2); // 首败 + 重试成功
        assertThat(sleeper.delays).hasSize(1);
        assertThat(sleeper.delays.get(0)).isBetween(0L, 100L); // 全抖动 [0, base=100]
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED); // 成功记账
    }

    @Test
    void transientExhausted_errorResult_breakerRecordedOnce() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor delegate = (r, m) -> {
            calls.incrementAndGet();
            throw new TransientException("持续超时");
        };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(3, 30000, () -> 0);
        RecordingSleeper sleeper = new RecordingSleeper();
        ResilientExecutor resilient = new ResilientExecutor(new ToolExceptionTriage(),
                new BackoffStrategy(), sleeper, new Random(1));
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker,
                new RetryPolicy(3, 50, 2.0, 10_000, false), 0L, resilient);

        ToolInvocation inv = exec.invoke(req("triangleArea"));

        assertThat(calls.get()).isEqualTo(3); // 退避重试至上限
        assertThat(inv.isError()).isTrue();
        assertThat(inv.error().kind()).isEqualTo(ToolErrorKind.TOOL_EXCEPTION);
        assertThat(inv.error().attempts()).isEqualTo(3);
        assertThat(sleeper.delays).containsExactly(50L, 100L); // 无抖动：指数 50→100
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED); // 记账按 invoke 终局一次（1<阈值3）
    }

    // ---- HTTP 状态分类（真实 LC4j 原语 + propagate 路径）----

    @Test
    void http500_retriedThroughLC4jWrap_thenSuccess() throws Exception {
        HttpToolStub tool = new HttpToolStub(1, 500);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(3, 30000, () -> 0);
        RecordingSleeper sleeper = new RecordingSleeper();
        ResilientExecutor resilient = new ResilientExecutor(new ToolExceptionTriage(),
                new BackoffStrategy(), sleeper, new Random(7));
        ResilientToolExecutor exec = new ResilientToolExecutor(propagating(tool, httpToolMethod()),
                breaker, new RetryPolicy(3, 20, 2.0, 1000, false), 0L, resilient);

        ToolInvocation inv = exec.invoke(ToolExecutionRequest.builder()
                .name("call").arguments("{}").id("c1").build());

        assertThat(inv.isError()).isFalse(); // 5xx 瞬态 → 重试成功
        assertThat(tool.calls.get()).isEqualTo(2);
        assertThat(sleeper.delays).hasSize(1);
        assertThat(breaker.state("call")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void http400_noRetry_noBreakerCount() throws Exception {
        HttpToolStub tool = new HttpToolStub(99, 400); // 恒 400
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        RecordingSleeper sleeper = new RecordingSleeper();
        ResilientExecutor resilient = new ResilientExecutor(new ToolExceptionTriage(),
                new BackoffStrategy(), sleeper, new Random(7));
        ResilientToolExecutor exec = new ResilientToolExecutor(propagating(tool, httpToolMethod()),
                breaker, new RetryPolicy(3, 20, 2.0, 1000, false), 0L, resilient);

        ToolInvocation inv = exec.invoke(ToolExecutionRequest.builder()
                .name("call").arguments("{}").id("c1").build());

        assertThat(inv.isError()).isTrue();
        assertThat(inv.error().kind()).isEqualTo(ToolErrorKind.HTTP_4XX); // 解包 ToolExecutionException 归因
        assertThat(inv.error().attempts()).isEqualTo(1); // 4xx 明确拒绝：不重试
        assertThat(sleeper.delays).isEmpty();
        assertThat(breaker.state("call")).isEqualTo(CircuitBreaker.State.CLOSED); // 外部有应答=健康，不记熔断
        assertThat(inv.content()).contains("HTTP 400");
    }

    @Test
    void http503_breakerCounted_afterExhausted() throws Exception {
        HttpToolStub tool = new HttpToolStub(99, 503);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        RecordingSleeper sleeper = new RecordingSleeper();
        ResilientExecutor resilient = new ResilientExecutor(new ToolExceptionTriage(),
                new BackoffStrategy(), sleeper, new Random(7));
        ResilientToolExecutor exec = new ResilientToolExecutor(propagating(tool, httpToolMethod()),
                breaker, RetryPolicy.noRetry(), 0L, resilient); // noRetry 隔离熔断语义

        ToolInvocation inv = exec.invoke(ToolExecutionRequest.builder()
                .name("call").arguments("{}").id("c1").build());

        assertThat(inv.error().kind()).isEqualTo(ToolErrorKind.HTTP_5XX);
        assertThat(breaker.state("call")).isEqualTo(CircuitBreaker.State.OPEN); // 服务端劣化 → 记熔断
    }

    // ---- 参数错误（真实 LC4j 原语 + wrap 路径）----

    @Test
    void malformedArguments_paramInvalid_noRetry_noBreaker() throws Exception {
        TriangleAreaTool tool = new TriangleAreaTool();
        Method m = TriangleAreaTool.class.getDeclaredMethod("triangleArea", double.class, double.class);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        RecordingSleeper sleeper = new RecordingSleeper();
        ResilientExecutor resilient = new ResilientExecutor(new ToolExceptionTriage(),
                new BackoffStrategy(), sleeper, new Random(7));
        ResilientToolExecutor exec = new ResilientToolExecutor(propagating(tool, m),
                breaker, new RetryPolicy(3, 20, 2.0, 1000, false), 0L, resilient);

        ToolInvocation inv = exec.invoke(ToolExecutionRequest.builder()
                .name("triangleArea").arguments("{\"base\":").id("c1").build());

        assertThat(inv.isError()).isTrue();
        assertThat(inv.error().kind()).isEqualTo(ToolErrorKind.PARAM_INVALID); // 模型侧问题
        assertThat(inv.error().attempts()).isEqualTo(1); // 不重试
        assertThat(sleeper.delays).isEmpty();
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED); // 不记熔断
        assertThat(inv.content()).contains("\"toolError\":true").contains("PARAM_INVALID");
    }

    // ---- 超时（守护线程硬中断）----

    @Test
    void timeout_hardInterrupted_retriedThenClassified() {
        ToolExecutor delegate = (r, m) -> {
            try {
                Thread.sleep(500); // 恒超预算
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 硬中断到达：恢复标志即视为失败
            }
            return "ok";
        };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(3, 30000, () -> 0);
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker,
                new RetryPolicy(2, 10, 2.0, 100, false), 80L); // 80ms 预算，重试 1 次

        ToolInvocation inv = exec.invoke(req("triangleArea"));

        assertThat(inv.isError()).isTrue();
        assertThat(inv.error().kind()).isEqualTo(ToolErrorKind.TIMEOUT);
        assertThat(inv.error().attempts()).isEqualTo(2); // 超时瞬态 → 退避重试至上限
        assertThat(inv.error().message()).contains("80ms");
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED); // 记账按 invoke 终局一次（1<阈值3）
    }

    // ---- 分诊器直接断言（规则表）----

    @Test
    void toolTriage_unwrapsLC4jWrapper_andClassifiesArguments() {
        ToolExceptionTriage triage = new ToolExceptionTriage();
        // ToolExecutionException(cause=ToolHttpException 400) → 解包归因 → 不可重试
        assertThat(triage.triage(new dev.langchain4j.exception.ToolExecutionException(
                new ToolHttpException(400, "bad request"))).category())
                .isEqualTo(ExceptionCategory.NON_RETRYABLE_CLIENT);
        // ToolExecutionException(cause=ToolHttpException 503) → 瞬态可重试
        assertThat(triage.triage(new dev.langchain4j.exception.ToolExecutionException(
                new ToolHttpException(503, "unavailable"))).category())
                .isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
        // ToolArgumentsException → 不可重试（反馈 LLM 自纠正）
        assertThat(triage.triage(new dev.langchain4j.exception.ToolArgumentsException(
                new IllegalArgumentException("bad args"))).category())
                .isEqualTo(ExceptionCategory.NON_RETRYABLE_CLIENT);
        // ToolTimeoutException → 瞬态可重试
        assertThat(triage.triage(new ToolTimeoutException("超时")).category())
                .isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
    }
}
