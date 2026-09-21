package com.agentdemo007.web;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.progress.ProgressEmitter;
import com.agentdemo007.common.progress.ProgressEvent;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.persistence.mq.ChatTurnFinalizer;
import com.agentdemo007.session.MemberLevelService;
import com.agentdemo007.session.profile.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话控制器（Phase 12·对外收口终端 + Phase 13 异步持久化接线 + #136 富事件 SSE 真流式）。
 *
 * <p>把 {@link PipelineResult} 翻译为 {@link UnifiedResponse}——任何流水线产出（正常/降级/短路）
 * 均 HTTP 200 + {@code code=0}（话术短路不暴露技术码），降级信息经 {@link ChatResponse} 的
 * {@code degraded}/{@code scenario} 元字段透出。与接入层 {@code InputSecurityFilter}/
 * {@code ValidationFilter} 的短路数据形状一致（第四原则·对外收口：终端与边界同形）。
 *
 * <p>Phase 13：流水线结束后调 {@link ChatTurnFinalizer#finalizeTurn} 收口会话持久化 + 审计刷出
 * （best-effort，停 MQ 不影响主接口 200）；该副作用经 seam 投递，不阻塞响应。
 *
 * <p>Phase 14：依赖 {@link PipelineExecutor} 接口而非具体编排器——由 {@code LangGraphConfig} 按
 * {@code agentdemo.pipeline.mode=linear|graph} 选择实现注入（线性 {@code PipelineOrchestrator}
 * 或图 {@code GraphExecutor}），控制器对此无感（④统一收口：换引擎不换出口）。
 *
 * <p>#136 富事件真流式：{@code /chat/stream} 不再手撸单事件 {@code data:} 字符串，而返回
 * {@link SseEmitter}——控制器立即返回 emitter（释放 Tomcat 请求线程，Spring MVC async），
 * {@link #sseTaskExecutor} 工作线程跑 {@link #runToSse}：注入 {@link SseProgressEmitter} 桥接
 * （每进度事件即 {@code send} 立即 flush，真流式非终端单 blob）→ 跑流水线 → 终端 {@code reply_ready}
 * 事件（负载 = {@link ChatResponse} JSON）→ {@code complete}。异常 → {@code completeWithError}（不吞）。
 * {@code /chat} 同步走 {@link ProgressEmitter#NO_OP}（零进度开销，不外泄）。
 *
 * <p><b>SSE 超时兜底（超时治理）</b>：LLM 慢调用/内部重试可能令流水线总时长超过 SSE 异步超时
 * （{@code app.sse.timeout-ms}）——此前超时后连接被容器掐断，流水线"晚到的成功"全部撞
 * already-completed 被吞，<b>前端什么都收不到</b>。现在 {@link #chatStream} 注册
 * {@link SseEmitter#onTimeout}：超时即向客户端补发 {@code reply_ready}（负载 = {@link ChatResponse}
 * 降级话术 {@link DegradationScenario#PIPELINE_TIMEOUT}）+ {@code complete}；共享 {@code dead}
 * 标志（onTimeout/onError/onCompletion 置位）让进度桥与 {@link #runToSse} 终端发送静默 no-op，
 * 不再刷 WARN。流水线本身不中断（副作用照常收口），只是结果不再投递——超时预算治理见
 * {@code LlmConfig}（单次 LLM 调用超时 60s + LC4j 内部重试关闭，重试/转移归网关层）。
 *
 * <ul>
 *   <li>{@code POST /chat} — 同步：返回完整 {@link UnifiedResponse}。</li>
 *   <li>{@code POST /chat/stream} — SSE：返回 {@link SseEmitter}，进度事件 + 终端 reply_ready 真流式
 *       （出口形状：多事件；负载同 /chat 的 {@link ChatResponse}，终态一致；超时兜底同形）。</li>
 * </ul>
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final long DEFAULT_SSE_TIMEOUT_MS = 120_000L;

    private final PipelineExecutor pipelineExecutor;
    private final ChatTurnFinalizer finalizer;
    private final ObjectMapper objectMapper;
    private final TaskExecutor sseTaskExecutor;
    private final AgentMetrics metrics;
    private final long sseTimeoutMs;
    private final MemberLevelService memberLevelService;
    private final UserProfileService profileService;

    /** 测试便利构造（缺省 SSE 超时 120s + 指标空实现 + 无画像服务）。 */
    public ChatController(PipelineExecutor pipelineExecutor, ChatTurnFinalizer finalizer,
                          ObjectMapper objectMapper, TaskExecutor sseTaskExecutor,
                          MemberLevelService memberLevelService) {
        this(pipelineExecutor, finalizer, objectMapper, sseTaskExecutor, DEFAULT_SSE_TIMEOUT_MS, AgentMetrics.NO_OP,
                memberLevelService, null);
    }

    /** 测试便利构造（含画像服务可空——画像缺位时按"无画像照常答"口径）。 */
    public ChatController(PipelineExecutor pipelineExecutor, ChatTurnFinalizer finalizer,
                          ObjectMapper objectMapper, TaskExecutor sseTaskExecutor,
                          MemberLevelService memberLevelService, UserProfileService profileService) {
        this(pipelineExecutor, finalizer, objectMapper, sseTaskExecutor, DEFAULT_SSE_TIMEOUT_MS, AgentMetrics.NO_OP,
                memberLevelService, profileService);
    }

    @Autowired
    public ChatController(PipelineExecutor pipelineExecutor, ChatTurnFinalizer finalizer,
                          ObjectMapper objectMapper,
                          @Qualifier("sseTaskExecutor") TaskExecutor sseTaskExecutor,
                          @Value("${app.sse.timeout-ms:120000}") long sseTimeoutMs,
                          AgentMetrics metrics,
                          MemberLevelService memberLevelService,
                          UserProfileService profileService) {
        this.pipelineExecutor = pipelineExecutor;
        this.finalizer = finalizer;
        this.objectMapper = objectMapper;
        this.sseTaskExecutor = sseTaskExecutor;
        this.metrics = metrics;
        this.sseTimeoutMs = sseTimeoutMs;
        this.memberLevelService = memberLevelService;
        this.profileService = profileService;
    }

    @PostMapping("/chat")
    public UnifiedResponse chat(@RequestBody ChatRequest request) {
        logArrival(request, "sync");
        return UnifiedResponse.success(run(request, ProgressEmitter.NO_OP, resolveSessionId(request)));
    }

    /**
     * 用户画像遗忘权（Phase 22 T102）：删除该用户全部画像字段（Redis 整哈希删除，不可恢复）。
     * 复用 {@link ChatRequest} 载体（只消费 userId；游客/缺省 → 语义化 no-op 成功）。
     * 经 {@code AccessGateFilter} 口令闸口（同 /chat）。
     * 存储异常收口为结构化失败（200 + {@code reset:false, reason}，不落全局 500——2026-09-21
     * review 修订；客户端据 reset 标志可重试）。
     */
    @PostMapping("/profile/reset")
    public UnifiedResponse resetProfile(@RequestBody ChatRequest request) {
        String userId = request.userId();
        log.info("画像遗忘权请求：userId={}", userId);
        if (profileService == null) {
            return UnifiedResponse.success(java.util.Map.of("reset", false, "reason", "画像服务未装配"));
        }
        try {
            profileService.reset(userId);
        } catch (Exception e) {
            log.warn("画像遗忘权执行失败（存储异常，结构化返回不抛 5xx）：userId={} reason={}",
                    userId, e.getMessage());
            return UnifiedResponse.success(java.util.Map.of(
                    "reset", false, "reason", "画像存储暂不可用，请稍后重试"));
        }
        return UnifiedResponse.success(java.util.Map.of("reset", true, "userId", userId == null ? "" : userId));
    }

    /** 请求到达打点（2026-09-18）：此前首条日志=查询改写 LLM 完成（晚 0.8~2.6s），
     *  "发起会话→首条日志"的延迟无法区分传输段与管线段——本行把到达时刻显式落日志。 */
    private void logArrival(ChatRequest request, String channel) {
        log.info("对话请求到达：channel={} sessionId={} msgLen={}", channel,
                request.sessionId(), (request.message() == null) ? 0 : request.message().length());
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        logArrival(request, "sse");
        // sessionId 在此计算一次并贯穿超时回调与流水线（request 无会话时生成随机 id，
        // 两处各自生成会得到不同 id——超时兜底话术须回传流水线同款 sessionId）
        String sessionId = resolveSessionId(request);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        // dead 标志：超时/断开/完成后置位，进度桥与终端发送据此静默 no-op（不再撞 already completed 刷屏）
        AtomicBoolean dead = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            dead.set(true);
            log.warn("SSE 超时（{}ms），补发超时兜底话术：sessionId={}", sseTimeoutMs, sessionId);
            sendTimeoutFallback(emitter, sessionId);
        });
        emitter.onError(t -> dead.set(true));
        emitter.onCompletion(() -> dead.set(true));
        // 工作线程跑 runToSse：控制器立即返回 emitter（Spring MVC async 释放请求线程），真流式 flush
        sseTaskExecutor.execute(() -> runToSse(emitter, request, sessionId, dead));
        return emitter;
    }

    /**
     * SSE 超时兜底：补发 {@code reply_ready}（降级话术 {@link DegradationScenario#PIPELINE_TIMEOUT}，
     * 终态与正常路径同形——前端只认 reply_ready 即可收到回复）+ {@code complete}。
     * best-effort：连接已断（send 抛）则吞——客户端已不可达，无事可做。
     * 包级可见供单测直接驱动。
     */
    void sendTimeoutFallback(SseEmitter emitter, String sessionId) {
        try {
            // 超时兜底：totalMs≈SSE 超时值（onTimeout 恰在超时点触发），firstTokenMs 无流式 token
            ChatResponse data = new ChatResponse(sessionId, DegradationScenario.PIPELINE_TIMEOUT.phrase(),
                    true, DegradationScenario.PIPELINE_TIMEOUT.name(), null, sseTimeoutMs, null);
            emitter.send(SseEmitter.event()
                    .name("reply_ready")
                    .data(objectMapper.writeValueAsString(data), MediaType.TEXT_PLAIN));
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 超时兜底话术发送失败（客户端可能已断开）：sessionId={} reason={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * SSE 工作线程体（#136 包级 seam，供单测直接驱动）。
     *
     * <p>注入 {@link SseProgressEmitter} 桥接（进度事件经 {@code send} 实时 flush）→ 跑流水线
     * → 终端 {@code reply_ready} 事件（负载 = {@link ChatResponse} JSON，终态与 /chat 一致）
     * → {@code complete}。任何异常 → {@code completeWithError}（不吞，让客户端明确感知失败）。
     * 流水线返回时 SSE 已超时/断开（{@code dead} 置位，超时话术已由 onTimeout 补发）→
     * 跳过终端发送（晚到的成功不再投递、不再撞 already completed）。
     */
    void runToSse(SseEmitter emitter, ChatRequest request, String sessionId, AtomicBoolean dead) {
        try {
            SseProgressEmitter progress = new SseProgressEmitter(emitter, objectMapper, dead);
            ChatResponse data = run(request, progress, sessionId);
            if (dead.get()) {
                log.info("流水线完成但 SSE 已结束，最终回复丢弃：sessionId={}", sessionId);
                return;
            }
            emitter.send(SseEmitter.event()
                    .name("reply_ready")
                    .data(objectMapper.writeValueAsString(data), MediaType.TEXT_PLAIN));
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE 流式处理异常，completeWithError：{}", e.getMessage(), e);
            emitter.completeWithError(e);
        }
    }

    /** 旧签名 seam（既有单测直接驱动）：内部自生成会话与存活标志。 */
    void runToSse(SseEmitter emitter, ChatRequest request) {
        runToSse(emitter, request, resolveSessionId(request), new AtomicBoolean(false));
    }

    /** 跑流水线并把终端结果 + 会话标识收口为 {@link ChatResponse}；进度经注入 emitter 发射。 */
    private ChatResponse run(ChatRequest request, ProgressEmitter progress, String sessionId) {
        // 接入层对话请求计数（POST /chat 与 /chat/stream 都汇经此）——可观测台「对话请求」读数源
        metrics.recordChatRequest();
        // 响应时间统计（后端口径）：totalMs=收到请求→终端回复就绪；firstTokenMs=首个流式 token
        long startMs = System.currentTimeMillis();
        FirstTokenTimingEmitter timed = new FirstTokenTimingEmitter(progress, startMs);
        PipelineContext context = new PipelineContext(sessionId, request.message());
        context.setUserId(request.userId()); // [[business-tools-workflow-dag]] 真接入：前端传 userId→工作流校验订单归属
        // Phase 21 等级解析（每请求一次）：登录态+会员服务，失败收敛 V0 fail-closed（seam 契约不抛异常）
        context.setMemberLevel(memberLevelService.levelOf(request.userId()));
        // Phase 22 用户画像（L3·每请求加载一次，fail-open）：读取失败/无画像 → null 照常答；
        // 画像永不承载权限语义（等级唯一来源=memberLevel）
        if (profileService != null) {
            context.setMemberProfile(profileService.renderForPrompt(request.userId()));
        }
        context.setEmitter(timed); // #136：NO_OP（同步 /chat）或 Sse 桥接（chatStream），经计时装饰
        PipelineResult result = pipelineExecutor.run(context);
        // Phase 13：终端后置钩子收口会话持久化 + 审计刷出（best-effort，不阻塞响应、停 MQ→主接口 200）
        finalizer.finalizeTurn(context, result);
        return new ChatResponse(sessionId, result.reply(), result.degraded(), result.scenario(),
                result.citations(), System.currentTimeMillis() - startMs, timed.firstTokenMs());
    }

    /**
     * 首个流式 token 计时装饰：透传进度事件，首个 {@link ProgressEvent.TokenChunk}
     * （reply_chunk）到达时记录相对请求起始的毫秒数——前端展示"首字耗时"用，
     * 口径与用户体感一致（打字指示消失、文字开始出现的时刻）。
     */
    private static final class FirstTokenTimingEmitter implements ProgressEmitter {

        private final ProgressEmitter delegate;
        private final long startMs;
        private Long firstTokenMs;

        FirstTokenTimingEmitter(ProgressEmitter delegate, long startMs) {
            this.delegate = delegate;
            this.startMs = startMs;
        }

        @Override
        public void emit(ProgressEvent event) {
            if (firstTokenMs == null && event instanceof ProgressEvent.TokenChunk) {
                firstTokenMs = System.currentTimeMillis() - startMs;
            }
            delegate.emit(event);
        }

        @Override
        public boolean closed() {
            return delegate.closed();
        }

        Long firstTokenMs() {
            return firstTokenMs;
        }
    }

    private String resolveSessionId(ChatRequest request) {
        return (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : UUID.randomUUID().toString();
    }
}
