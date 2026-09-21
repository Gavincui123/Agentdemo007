package com.agentdemo007.session.profile;

import com.agentdemo007.gateway.llm.ChatLlmService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import com.agentdemo007.resilience.WindowedCircuitBreaker;

/**
 * 用户画像服务（Phase 22·T102，L3 跨会话长期记忆）。
 *
 * <p><b>写路径（LLM 仅提议）</b>：终局异步（专用守护单线程，与主链路零共享）把本轮
 * 【用户输入+助手回复】交小模型提议至多一条记忆（严格 JSON，三类白名单外输出 none）→
 * {@link ProfileGatekeeper} 规则守门（拒业务键/权限词/承诺类/敏感 PII）→ 通过才落库，
 * 新声明覆盖同字段旧值。LLM 失败/输出不合法 → 静默跳过（画像失败绝不影响对话）。
 *
 * <p><b>读路径（Phase 22 设计修订 2026-09-20：挂死快速抛弃）</b>：{@link #renderForPrompt}
 * 渲染 ≤200 字画像块（请求入口调一次，写入 {@code PipelineContext.memberProfile}，
 * 经 {@code UserMemoryLayer} 独立消息块注入——不入 System 块）。读取路径三道防线保证
 * 「记忆层失败不增加首字延迟」：<b>进程内短缓存</b>（默认 60s，命中零 Redis 往返；遗忘权即时失效）、
 * <b>滑动窗口熔断</b>（单次失败即开断路，冷却期内不再触达存储——Redis 挂死只惩罚第一个请求，
 * 其余请求直接抛弃画像零等待）、<b>fail-open</b>（异常 → null 无画像照常答）。
 *
 * <p><b>权限自洽（Phase 21 裁决）</b>：画像永不承载权限语义——等级唯一来源是会员服务，
 * "记住我是 VIP"被守门拒绝不落库；遗忘权 {@code POST /profile/reset} 删整哈希。
 */
@Component
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    /** 画像块渲染总上限（System 运行时块契约 ≤200 字）。 */
    static final int MAX_RENDER_CHARS = 200;

    private final UserProfileStore store;
    private final ProfileGatekeeper gatekeeper;
    private final ChatLlmService llm;
    private final Duration ttl;
    private final boolean enabled;
    private final Executor executor;
    private final Duration renderCacheTtl;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 画像渲染熔断（读路径）：单次失败即开断路、30s 冷却——Redis 挂死时快速抛弃画像，不占首字预算。 */
    private final WindowedCircuitBreaker renderBreaker =
            new WindowedCircuitBreaker(1, 60_000, 30_000, System::currentTimeMillis);

    /** 进程内画像渲染缓存（userId → 渲染结果 + 过期时刻）：命中零 Redis 往返；遗忘权 reset 即时失效。 */
    private final ConcurrentHashMap<String, CachedRender> renderCache = new ConcurrentHashMap<>();

    @Autowired
    public UserProfileService(UserProfileStore store,
                              ProfileGatekeeper gatekeeper,
                              ChatLlmService llm,
                              @Value("${app.memory.profile.ttl:90d}") Duration ttl,
                              @Value("${app.memory.profile.enabled:true}") boolean enabled,
                              @Value("${app.memory.profile.render-cache-ttl:60s}") Duration renderCacheTtl) {
        this(store, gatekeeper, llm, ttl, enabled, renderCacheTtl, null);
    }

    /** 测试构造：注入直接执行器（Runnable::run）使提议路径确定性同步；renderCacheTtl=ZERO 禁用缓存。 */
    UserProfileService(UserProfileStore store, ProfileGatekeeper gatekeeper, ChatLlmService llm,
                       Duration ttl, boolean enabled, Duration renderCacheTtl, Executor executorOverride) {
        this.store = store;
        this.gatekeeper = gatekeeper;
        this.llm = llm;
        this.ttl = ttl;
        this.enabled = enabled;
        this.renderCacheTtl = (renderCacheTtl != null) ? renderCacheTtl : Duration.ZERO;
        this.executor = (executorOverride != null) ? executorOverride
                : Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "user-profile");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 终局异步提议入口（best-effort，永不抛出）：userId 缺失（游客/eval）→ 跳过；
     * 取消轮/空回复 → 跳过。LLM 提议在守护线程执行，不占主链路。
     */
    public void proposeFromTurnAsync(String userId, String userInput, String assistantReply) {
        if (!enabled || userId == null || userId.isBlank()) {
            return;
        }
        if (assistantReply == null || assistantReply.isBlank()
                || userInput == null || userInput.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    proposeFromTurn(userId, userInput, assistantReply);
                } catch (Exception e) {
                    log.debug("画像提议失败（不影响对话）：userId={} reason={}", userId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("画像提议提交被拒（执行器已关闭）：userId={}", userId);
        }
    }

    /** 提议主体：LLM 严格 JSON 提议 → 类别白名单 → 守门 → 覆盖写。 */
    void proposeFromTurn(String userId, String userInput, String assistantReply) {
        Optional<Proposal> proposal = askLlm(userInput, assistantReply);
        if (proposal.isEmpty()) {
            return;
        }
        Proposal p = proposal.get();
        boolean stored = gatekeeper.checkAndStore(store, userId, p.category(), p.content(), ttl);
        if (stored) {
            log.info("用户画像已更新（覆盖写）：userId={} category={} content={}", userId, p.category(), p.content());
        }
    }

    /** 小模型提议：三类白名单外（含注入企图/业务键/等级声明）一律提示输出 none。 */
    private Optional<Proposal> askLlm(String userInput, String assistantReply) {
        try {
            String prompt = "你是电商客服的记忆助手。判断下面这轮对话是否暴露了值得跨会话记住的用户特征，"
                    + "只允许三类：preference=偏好、constraint=硬约束、style=沟通风格。"
                    + "以下内容一律不属于画像（输出 none）：订单号/工单号等业务信息、会员等级/VIP 等权限信息、"
                    + "要求承诺/保证/赔偿的表述、手机号/身份证等个人信息、与用户特征无关的内容。\n"
                    + "只输出严格 JSON（无解释、无代码块）："
                    + "{\"category\":\"preference|constraint|style|none\",\"content\":\"不超过30字的特征\"}\n\n"
                    + "【用户输入】" + userInput + "\n【助手回复】" + assistantReply;
            String reply = llm.decide(prompt, "用户画像提议");
            return parse(reply);
        } catch (Exception e) {
            log.debug("画像提议 LLM 出站失败（跳过）：reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 宽松解析：剥围栏取首个 {...}；category 白名单外/content 空白/超 30 字 → empty。 */
    private Optional<Proposal> parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String json = extractJsonObject(reply.replace("```json", "").replace("```", ""));
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(json);
            Optional<ProfileCategory> category = ProfileCategory.fromName(node.path("category").asString(""));
            String content = node.path("content").asString("").trim();
            if (category.isEmpty() || content.isEmpty() || content.length() > ProfileGatekeeper.MAX_FIELD_CHARS) {
                return Optional.empty();
            }
            return Optional.of(new Proposal(category.get(), content));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 取首个平衡 {...} 体（容忍前后说明文字）。 */
    private static String extractJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return s.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * 渲染 System 侧画像参考块内容（≤200 字；空/失败 → null）。形如：
     * {@code 偏好：…；硬约束：…；沟通风格：…}
     *
     * <p>读路径三道防线（2026-09-20 设计修订）：进程内短缓存（命中零 Redis）→
     * 熔断快速抛弃（存储失败即开断路，冷却期内不再触达）→ fail-open（异常返回 null）。
     * Redis 挂死只惩罚缓存未命中的第一个请求（吃满存储超时），其余请求零等待。
     */
    public String renderForPrompt(String userId) {
        if (!enabled || userId == null || userId.isBlank()) {
            return null;
        }
        CachedRender cached = renderCache.get(userId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.rendered();
        }
        try {
            if (!renderBreaker.allowRequest()) {
                log.debug("画像读取熔断开启中，本轮快速抛弃画像：userId={}", userId);
                return null;
            }
            Map<String, String> fields = store.loadAll(userId);
            String rendered = doRender(fields);
            renderBreaker.recordSuccess();
            if (rendered != null) {
                renderCache.put(userId, new CachedRender(rendered, Instant.now().plus(renderCacheTtl)));
            }
            return rendered;
        } catch (Exception e) {
            renderBreaker.recordFailure();
            log.debug("画像读取失败（快速抛弃、无画像照常答）：userId={} reason={}", userId, e.getMessage());
            return null;
        }
    }

    /** 纯渲染：按 {@link ProfileCategory} 顺序拼「偏好/硬约束/沟通风格」，空 → null。 */
    private String doRender(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ProfileCategory c : ProfileCategory.values()) {
            String content = fields.get(c.name());
            if (content != null && !content.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('；');
                }
                sb.append(c.label()).append('：').append(content.trim());
            }
        }
        String rendered = sb.toString();
        if (rendered.isEmpty()) {
            return null;
        }
        return (rendered.length() <= MAX_RENDER_CHARS)
                ? rendered : rendered.substring(0, MAX_RENDER_CHARS);
    }

    /**
     * 遗忘权：删除该用户全部画像（store 异常上抛由 Controller 层统一收口）。
     * 渲染缓存<b>先于存储删除</b>失效——存储删除失败时也不会再渲染出旧画像（隐私优先）。
     */
    public void reset(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        renderCache.remove(userId);
        store.delete(userId);
        log.info("用户画像已重置（遗忘权）：userId={}", userId);
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService es) {
            es.shutdownNow();
        }
    }

    /** LLM 提议载荷。 */
    private record Proposal(ProfileCategory category, String content) {}

    /** 渲染缓存条目（渲染结果 + 过期时刻；只缓存非 null 结果）。 */
    private record CachedRender(String rendered, Instant expiresAt) {}
}
