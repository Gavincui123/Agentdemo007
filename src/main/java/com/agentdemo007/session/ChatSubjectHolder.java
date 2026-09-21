package com.agentdemo007.session;

/**
 * 对话主体的请求窗口 holder（Phase 21 等级门·@Tool 通道桥接）。
 *
 * <p><b>写入窗口（单一写者）</b>：{@code ToolExecutionStep.process} 进入工具执行前从
 * {@code PipelineContext} 写入，finally 清除——窗口外/未设置读取一律收敛 {@link ChatSubject#ANONYMOUS}
 * （fail-closed V0，绝不放大权限）。跨线程传播由 {@code ResilientToolExecutor.callOnce} 在
 * 守护线程跳变前快照、执行时回放（超时模式 {@code app.tool.timeout-ms} 默认开启，跳变是主路径）。
 *
 * <p>为什么不用 MDC/RequestContextHolder：工具守护线程是池化复用的（ThreadLocal 不随线程池传播），
 * SSE 工作线程无请求上下文——显式 set/snapshot/restore 三步是唯一在两种跳变下都正确的口径。
 */
public final class ChatSubjectHolder {

    private static final ThreadLocal<ChatSubject> HOLDER = new ThreadLocal<>();

    private ChatSubjectHolder() {
    }

    /** 当前请求主体（未设置/已清除 → ANONYMOUS；永不返回 null）。 */
    public static ChatSubject current() {
        ChatSubject subject = HOLDER.get();
        return (subject != null) ? subject : ChatSubject.ANONYMOUS;
    }

    /** 写入请求窗口主体（null 归一 ANONYMOUS）。 */
    public static void set(ChatSubject subject) {
        HOLDER.set((subject != null) ? subject : ChatSubject.ANONYMOUS);
    }

    /** 清除（防线程池串号：清除后未设置的读取必须收敛 ANONYMOUS）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
