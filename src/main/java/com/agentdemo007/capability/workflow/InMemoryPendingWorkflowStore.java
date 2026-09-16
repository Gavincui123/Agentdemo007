package com.agentdemo007.capability.workflow;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 待续跑工作流存储 in-memory 实现（[[business-tools-workflow-dag]] §2.5·dev/最小路径）。
 *
 * <p>ConcurrentHashMap 按 sessionId 存 {@link PendingWorkflow}（进程内，单实例 dev 足够）。
 * 真接入（多实例/持久）换 Redis/session 实现，不换 seam/调用方（{@link PendingWorkflowStore}）。
 *
 * <p>非线程安全场景由编排器单请求单线程保证；ConcurrentMap 仅供多请求并发读写的内存可见性。
 */
@Component
public class InMemoryPendingWorkflowStore implements PendingWorkflowStore {

    private final ConcurrentMap<String, PendingWorkflow> store = new ConcurrentHashMap<>();

    @Override
    public void put(String sessionId, PendingWorkflow pending) {
        if (sessionId != null && pending != null) {
            store.put(sessionId, pending);
        }
    }

    @Override
    public Optional<PendingWorkflow> get(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId != null) {
            store.remove(sessionId);
        }
    }
}
