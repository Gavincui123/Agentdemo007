package com.agentdemo007.capability.workflow;

import java.util.Optional;

/**
 * 待续跑工作流存储 seam（[[business-tools-workflow-dag]] §2.5·多轮澄清续跑·session 级）。
 *
 * <p>{@link WorkflowExecutionStep} Turn 1 entity-gate 澄清时 {@link #put}（按 sessionId 存 pending intent）；
 * Turn 2 由 {@link WorkflowExecutionStep} 状态机按当前轮 routePlan 决策续跑/切换/放弃（{@link #get} +
 * {@link #remove}）。
 * dev 用 {@link InMemoryPendingWorkflowStore}（ConcurrentHashMap，进程内），真接入换 Redis/session 存储
 * 换 impl 不换 seam/调用方。
 *
 * <p>收口：pending 真相源只经此 seam；未知 sessionId 幂等返 empty 不抛（§5.12 每步降级，不阻塞主链路）。
 * {@link #NO_OP} 供单测便利（零存储，gate 逻辑不依赖存储侧行为）。
 */
public interface PendingWorkflowStore {

    /** 存入 pending（Turn 1 澄清时）。 */
    void put(String sessionId, PendingWorkflow pending);

    /** 取 pending（Turn 2 续跑时）；不存在返 empty。 */
    Optional<PendingWorkflow> get(String sessionId);

    /** 取后删（一次性续跑，防重复触发）。 */
    void remove(String sessionId);

    /** NO_OP（单测便利：gate 逻辑不依赖存储时用，零存储零异常）。 */
    PendingWorkflowStore NO_OP = new PendingWorkflowStore() {
        @Override public void put(String sessionId, PendingWorkflow pending) { }
        @Override public Optional<PendingWorkflow> get(String sessionId) { return Optional.empty(); }
        @Override public void remove(String sessionId) { }
    };
}
