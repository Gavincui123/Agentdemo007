package com.agentdemo007.observability.trace;

import java.util.Map;

/**
 * 分布式链路上下文传播 seam（Phase 15·跨进程 traceId 不断，④统一收口 + §5.14 引擎无关）。
 *
 * <p>消息出站（生产者投递）经 {@link #inject} 把当前 trace 上下文写入 {@code carrier}（headers Map）；
 * 消息入站（消费者接收）经 {@link #extractTraceId} 从 carrier 取回 traceId，再经 {@link #restoreScope}
 * 在消费线程恢复为 current（写 MDC），使消费侧的 {@link com.agentdemo007.common.trace.TraceId#current()}
 * 与生产侧一致——跨进程 traceId 不断，Jaeger 可凭 traceId 检索全链路节点。
 *
 * <p>引擎无关（§5.14）：carrier 形状对齐 OTel {@code TextMapPropagator}（W3C {@code traceparent}），
 * dev/no-OTel 实现 {@link MdcTraceContextPropagator} 以 {@link com.agentdemo007.common.trace.TraceId#current()}
 * 合成 traceparent、回写 MDC；prod 待加 OTel 依赖后委托 {@code W3CTraceContextPropagator}——换实现不换收口。
 * 所有跨进程 trace 传播只经此 seam，禁止散落 MDC 操作（④）。
 *
 * <p>②每步降级：{@link #inject}/{@link #restoreScope} 内部兜底，永不抛、永不影响消息投递/消费。
 * {@link #NO_OP} 为禁用 trace 场景的空实现（写入被丢弃、scope 无副作用），永不外泄、永不为 null。
 */
public interface TraceContextPropagator {

    /** W3C Trace Context 头名（carrier 中的键）。 */
    String TRACEPARENT_HEADER = "traceparent";

    /**
     * 注入当前 trace 上下文到 carrier（headers Map，最佳努力，永不抛）。
     *
     * @param carrier 生产者投递时携带的头映射（可被实现写入 {@code traceparent}）
     */
    void inject(Map<String, String> carrier);

    /**
     * 从 carrier 提取 traceId（缺失/非法返回 null）。
     *
     * @param carrier 消费者接收到的头映射
     * @return traceId，或 null
     */
    String extractTraceId(Map<String, String> carrier);

    /**
     * 在当前线程恢复 carrier 中的 trace 为 current（写 MDC），返回 scope；关闭即还原调用前状态。
     * 无 traceparent 时返回无副作用 scope（不碰 MDC）。best-effort，永不抛。
     *
     * @param carrier 消费者接收到的头映射
     * @return try-with-resources 关闭后还原 MDC 的 scope
     */
    AutoCloseable restoreScope(Map<String, String> carrier);

    /** 空实现：不注入/不提取/不恢复 MDC（供非装配单测与降级场景，永不为 null）。 */
    TraceContextPropagator NO_OP = new TraceContextPropagator() {
        @Override
        public void inject(Map<String, String> carrier) {
            // no-op sink
        }

        @Override
        public String extractTraceId(Map<String, String> carrier) {
            return null;
        }

        @Override
        public AutoCloseable restoreScope(Map<String, String> carrier) {
            return () -> {
            };
        }
    };
}
