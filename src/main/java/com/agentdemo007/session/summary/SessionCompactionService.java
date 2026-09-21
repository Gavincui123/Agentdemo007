package com.agentdemo007.session.summary;

import com.agentdemo007.session.cache.SessionCacheService;
import com.agentdemo007.session.cache.SessionMemory;
import com.agentdemo007.session.cache.SessionWindower;
import com.agentdemo007.session.model.ChatMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 会话记忆压缩服务（Phase 22·T100 终局异步压缩）。
 *
 * <p><b>触发</b>（T99 真实 usage 轨）：终局钩子读本轮主模型 usage（{@code lastUsageTokens}），
 * ≥ {@code app.memory.window-budget-tokens} × {@code compaction-threshold}（默认 80%）→ 提交异步压缩。
 * 80% 天然留 20% 余量吸收竞态：下一轮到达时摘要未就绪 → 读侧降级"无摘要多喂原文"（不等待不阻塞）。
 * <b>usage 口径</b>（2026-09-21 review 注记）= 主模型 {@code totalTokenCount}（prompt+completion
 * 全量，含 system/RAG/工具/回答），比 L1 窗口预算（仅历史段）口径宽——触发偏早属保守方向，
 * 滑出为空自然 no-op；勿把阈值 960 误读为"历史段 token 数"。
 *
 * <p><b>执行</b>（专用单线程守护池，与主链路零共享——不出现在首字延迟账上）：
 * 读 {@link SessionMemory} → 按窗口预算分轮（与读侧同一 {@link SessionWindower}，口径一致）→
 * 滑出前缀 → {@code summarizeRolling(旧摘要, 滑出轮次)} 增量合并（永不重压全量，LLM 调用在锁外）→
 * 业务键白名单补齐（T101：滑出文本中的订单号压缩后必须仍在，运行时缺啥补啥）→
 * {@link SessionCacheService#mergeSave} 锁内原子写回。
 *
 * <p><b>竞态安全</b>（2026-09-21 review 修订，承诺与实现对齐）：压缩读值后记 baseCount；
 * 写回收口为 {@code mergeSave}——同会话条纹锁内 load 最新值、把压缩期间新追加的消息拼回窗口尾
 * （append 与写回互斥，append 的 get/set 不可能横跨 save 用旧值覆盖压缩结果）；
 * 摘要生成失败/垃圾 → 沿用旧摘要（宁缺毋滥），业务键照常补齐。
 * 单线程执行器同时串行化同会话的多次压缩。JVM 内完备；多实例部署为残余边界
 * （需 WATCH/Lua/list 重构，demo 单实例不涉及）。
 *
 * <p>任何异常仅告警——记忆压缩失败不影响对话正确性（L0 注册表 + 读侧多喂原文双兜底）。
 */
@Component
public class SessionCompactionService {

    private static final Logger log = LoggerFactory.getLogger(SessionCompactionService.class);

    private final SessionCacheService cache;
    private final SummaryHook summaryHook;
    private final SessionWindower windower;
    private final boolean enabled;
    private final int triggerTokens;
    private final int summaryMaxChars;
    private final Executor executor;

    @Autowired
    public SessionCompactionService(SessionCacheService cache,
                                    SummaryHook summaryHook,
                                    SessionWindower windower,
                                    @Value("${app.memory.compaction.enabled:true}") boolean enabled,
                                    @Value("${app.memory.window-budget-tokens:1200}") int windowBudgetTokens,
                                    @Value("${app.memory.compaction-threshold:0.8}") double threshold,
                                    @Value("${app.memory.summary-max-chars:200}") int summaryMaxChars) {
        this(cache, summaryHook, windower, enabled, windowBudgetTokens, threshold, summaryMaxChars, null);
    }

    /** 测试构造：注入直接执行器（Runnable::run）使触发路径确定性同步。 */
    SessionCompactionService(SessionCacheService cache, SummaryHook summaryHook, SessionWindower windower,
                             boolean enabled, int windowBudgetTokens, double threshold, int summaryMaxChars,
                             Executor executorOverride) {
        this.cache = cache;
        this.summaryHook = summaryHook;
        this.windower = windower;
        this.enabled = enabled;
        this.triggerTokens = (int) Math.round(Math.max(0.1, Math.min(1.0, threshold)) * windowBudgetTokens);
        this.summaryMaxChars = Math.max(50, summaryMaxChars);
        this.executor = (executorOverride != null) ? executorOverride
                : Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "memory-compaction");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 压缩触发 token 阈值（预算 × 阈值；测试与可观测用）。 */
    int triggerTokens() {
        return triggerTokens;
    }

    /**
     * 终局触发入口（best-effort，永不抛出、永不阻塞主链路）：
     * 本轮主模型 usage ≥ 触发阈值 → 提交异步压缩；usage 缺失（话术短路/取消/引擎未回 usage）→ 跳过。
     */
    public void maybeCompactAsync(String sessionId, Integer lastUsageTokens) {
        if (!enabled || sessionId == null || lastUsageTokens == null) {
            return;
        }
        if (lastUsageTokens < triggerTokens) {
            return;
        }
        log.info("触发会话记忆压缩：sessionId={} lastUsageTokens={} threshold={}",
                sessionId, lastUsageTokens, triggerTokens);
        try {
            executor.execute(() -> {
                try {
                    compact(sessionId);
                } catch (Exception e) {
                    log.warn("会话记忆压缩失败（不影响对话）：sessionId={} reason={}", sessionId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("压缩任务提交被拒（执行器已关闭）：sessionId={}", sessionId);
        }
    }

    /**
     * 压缩主体（同步执行，包级可见供测试直驱）：读 → 窗口分轮 → 滑出前缀增量摘要 → 竞态合并写回。
     * 滑出为空（窗口内全量装得下）→ 无操作。
     */
    void compact(String sessionId) {
        SessionMemory memory = cache.loadMemory(sessionId);
        if (memory.messages().isEmpty()) {
            return;
        }
        int baseCount = memory.messages().size(); // 竞态基线：本次压缩读到的消息数
        List<ChatMessage> window = windower.window(memory.messages());
        List<ChatMessage> slidOut = memory.messages().subList(0, memory.messages().size() - window.size());
        if (slidOut.isEmpty()) {
            log.debug("会话记忆无可压缩（窗口未满）：sessionId={} messages={}", sessionId, baseCount);
            return;
        }
        String merged = mergeSummary(memory.summary(), slidOut); // LLM 调用在锁外（秒级，不阻塞 append）
        // 竞态合并写回（原子收口）：锁内重读最新值，压缩期间 append 的新消息原样拼回窗口尾（不丢历史）
        SessionMemory saved = cache.mergeSave(sessionId, latest -> {
            List<ChatMessage> concurrent = (latest.messages().size() > baseCount)
                    ? latest.messages().subList(baseCount, latest.messages().size())
                    : List.<ChatMessage>of();
            List<ChatMessage> kept = new ArrayList<>(window);
            kept.addAll(concurrent);
            return new SessionMemory(merged, kept);
        });
        log.info("会话记忆压缩完成：sessionId={} 滑出 {} 条 → 摘要 {} 字，保留 {} 条（含并发追加 {} 条）",
                sessionId, slidOut.size(), (merged == null) ? 0 : merged.length(),
                saved.messages().size(), saved.messages().size() - window.size());
    }

    /**
     * 摘要增量合并 + 业务键白名单收口（T101）：
     * LLM 合并失败/垃圾 → 沿用旧摘要；滑出文本中的业务键若在新摘要中缺失 → 运行时补
     * 「涉及单号」行（确定性保证，golden 钉死）；总长按契约截断（业务键行保留，不因截断丢失）。
     *
     * @return 合并后摘要；完全无内容（无旧摘要且 LLM 失败且无业务键）→ null（本周期无摘要）
     */
    String mergeSummary(String oldSummary, List<ChatMessage> slidOut) {
        Optional<String> summarized = summaryHook.summarizeRolling(oldSummary, slidOut);
        String text = summarized.orElse(oldSummary);
        boolean hasText = text != null && !text.isBlank();
        List<String> slidTexts = slidOut.stream().map(ChatMessage::content).toList();
        Set<String> keys = BusinessKeyExtractor.extractAll(slidTexts);
        Set<String> missing = new java.util.LinkedHashSet<>(keys);
        missing.removeAll(BusinessKeyExtractor.extract(hasText ? text : ""));
        String keyLine = missing.isEmpty() ? "" : "；涉及单号: " + String.join("、", missing);
        if (!hasText && keyLine.isEmpty()) {
            return null; // 宁缺毋滥：本周期无摘要（读侧多喂原文，correctness 由 L0 兜底）
        }
        String body = hasText ? text : "";
        int budget = summaryMaxChars - keyLine.length();
        if (budget < 0) {
            // 业务键行自身超长（极端：单号过多）——键优先于正文
            return keyLine.substring(0, Math.min(keyLine.length(), summaryMaxChars));
        }
        if (body.length() > budget) {
            body = body.substring(0, budget);
        }
        return body + keyLine;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService es) {
            es.shutdownNow();
        }
    }
}
