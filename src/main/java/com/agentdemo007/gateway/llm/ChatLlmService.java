package com.agentdemo007.gateway.llm;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.gateway.core.GatewayRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import com.agentdemo007.gateway.selector.ModelSelector;
import com.agentdemo007.gateway.selector.SelectionCriteria;
import com.agentdemo007.intent.Intent;

import java.util.List;
import java.util.Optional;

/**
 * LLM 统一入口（第六层对外服务门面）。
 *
 * <p>收口所有出站调用：① 强制 {@link PromptSanitizer} 包裹用户内容（注入隔离，防逃逸）；
 * ② 按意图路由选模型（有路由规则用其目标，否则经 {@link ModelSelector} 在启用模型中选）；
 * ③ 经 {@link UnifiedModelGateway} 执行（预算关卡 + 故障转移）。
 *
 * <p>同步入口已就绪；流式/分类调用在引入 LangChain4j 流式契约后扩展，
 * 收口出口形状（{@code String} 回复）不变。
 */
public class ChatLlmService {

    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final UnifiedModelGateway gateway;
    private final ModelConfigCenter center;
    private final ModelSelector selector;
    private final PromptSanitizer sanitizer;
    private final boolean thinkingEnabled;
    private final int maxTokens;

    public ChatLlmService(UnifiedModelGateway gateway, ModelConfigCenter center,
                           ModelSelector selector, PromptSanitizer sanitizer) {
        this(gateway, center, selector, sanitizer, true, DEFAULT_MAX_TOKENS); // 默认开思考（非闲聊意图）
    }

    /**
     * 注入思考开关（{@code llm.thinking.enabled}，默认 true）：非闲聊意图由本开关定是否开思考；
     * 闲聊(CHIT_CHAT)恒关思考。每请求据此算定 {@code disableThinking} 注入 {@link GatewayRequest}。
     */
    public ChatLlmService(UnifiedModelGateway gateway, ModelConfigCenter center,
                           ModelSelector selector, PromptSanitizer sanitizer, boolean thinkingEnabled) {
        this(gateway, center, selector, sanitizer, thinkingEnabled, DEFAULT_MAX_TOKENS);
    }

    /**
     * 注入 maxTokens（{@code llm.max-tokens}，默认 1024）：推理模型思考开时 1024 常令 reasoning
     * 耗尽预算→空 content（LlmUnavailable→切备/容灾耗尽）；调高（如 4096）给 reasoning+答案留预算，
     * 或关思考（{@code thinking.enabled=false}）根除。意图驱动的 disableThinking 与 maxTokens 正交。
     */
    public ChatLlmService(UnifiedModelGateway gateway, ModelConfigCenter center,
                           ModelSelector selector, PromptSanitizer sanitizer,
                           boolean thinkingEnabled, int maxTokens) {
        this.gateway = gateway;
        this.center = center;
        this.selector = selector;
        this.sanitizer = sanitizer;
        this.thinkingEnabled = thinkingEnabled;
        this.maxTokens = maxTokens;
    }

    /** 同步对话：返回模型回复文本。强制 {@link PromptSanitizer} 包裹（注入隔离）。 */
    public String chat(String prompt, Intent intent) {
        return chat(prompt, intent, "回答生成");
    }

    /** 同步对话（带场景标签，随 {@code GatewayRequest} 进「LLM出站」日志）：非回答类用途（如会话摘要）显式标注。 */
    public String chat(String prompt, Intent intent, String scene) {
        return invoke(sanitizer.sanitize(prompt), intent, disableThinkingFor(intent), scene);
    }

    /**
     * 同步对话（不二次包裹）：用于已由 {@code ContextBuilder} 三层隔离 + 定界符包裹的组装 prompt。
     *
     * <p>组装 prompt 内含 System/Runtime/His/RAG/Tool/User 多层，二次 {@link PromptSanitizer#sanitize}
     * 会把整条（含 System 锚点）当用户数据包裹，破坏层级结构——故本入口跳过包裹，
     * 由 {@code ContextBuilder} 的 {@code UserInstructionLayer} 已保证用户层隔离（§5.5）。
     * prod LangChain4j 桥接应改为接收 {@code List<ChatMessage>} 结构化消息（延后薄层）。
     */
    public String chatRaw(String prompt, Intent intent) {
        return chatRaw(prompt, intent, "回答生成");
    }

    /**
     * 同步对话（不二次包裹 + 场景标签）：组装 prompt 的非「回答生成」用途（如澄清话术生成）显式标注，
     * 随 {@code GatewayRequest} 进「LLM出站」日志——同轮多次出站按用途可辨。包裹语义同 {@link #chatRaw(String, Intent)}。
     */
    public String chatRaw(String prompt, Intent intent, String scene) {
        return invoke(prompt, intent, disableThinkingFor(intent), scene);
    }

    /**
     * 流式生成（[[q2-token-streaming]]·不二次包裹，同 {@link #chatRaw}）：经 {@code gateway.stream} 主模型流式，
     * 逐 token 经 {@link StreamingReplyHandler#onPartialResponse} 回调。意图驱动关思考同 {@code chatRaw}。
     *
     * <p>流式<b>主模型 only、无中途故障转移</b>；同步异常（无可用模型/预算超限/leaf 前置抛）→捕获转
     * {@code handler.onError}，调用方（{@code OutputStep}）据此回退阻塞 {@code chatRaw}（有完整主备容灾）→韧性不丢。
     * 与 {@link #chat} 的"意图驱动思考"正交：流式按意图定思考开关（非闲聊由 {@code thinkingEnabled} 定）。
     */
    public void chatRawStream(String prompt, Intent intent, StreamingReplyHandler handler) {
        Optional<RouteRule> rule = center.routeFor(intent);
        String primary;
        try {
            primary = resolvePrimary(intent, rule);
        } catch (Throwable e) {
            handler.onError(e); // 无可用模型等同步错→统一 onError（调用方回退阻塞）
            return;
        }
        // 流式主模型 only，failover 不用于流式（无中途切备），传单主策略占位
        FailoverPolicy failover = new FailoverPolicy.Builder(primary).build();
        GatewayRequest request = new GatewayRequest(primary, prompt, maxTokens,
                failover, center.flowControl(), disableThinkingFor(intent), "回答生成");
        try {
            gateway.stream(request, handler);
        } catch (Throwable e) {
            handler.onError(e); // 预算超限/leaf 前置抛→onError（调用方回退阻塞 chatRaw）
        }
    }

    /**
     * 决策/控制调用（意图识别 / 改写 / 路由规划等）：恒关思考 + 路由小模型（CHIT_CHAT 通道）。
     *
     * <p>决策类调用是快速分类/规划任务，非推理——开思考既浪费 token 又可能令推理模型 reasoning
     * 耗尽预算→空 content（{@code LlmUnavailable}→切备/容灾耗尽）。故<b>所有决策调用经此入口</b>，
     * {@code disableThinking} 恒为 {@code true}（不受 {@code llm.thinking.enabled} 开关影响）。
     * 与 {@link #chat} 的"意图驱动思考"正交：chat 按用户意图定思考开关，decide 恒关。
     * grep {@code decide(} 可审计全部决策调用均关思考（用户铁律：意图识别/决策路由用模型一律关思考）。
     */
    public String decide(String prompt) {
        return decide(prompt, "决策");
    }

    /**
     * 决策/控制调用（带场景标签）：{@code scene}（如 查询改写/意图识别/路由计划）随
     * {@code GatewayRequest} 进「LLM出站」日志——同轮多次小模型出站按用途可辨。
     */
    public String decide(String prompt, String scene) {
        return invoke(sanitizer.sanitize(prompt), Intent.CHIT_CHAT, true, scene);
    }

    /** 意图驱动思考开关算定：闲聊恒关；非闲聊由 {@code thinkingEnabled} 定（true=开，false=关）。 */
    private boolean disableThinkingFor(Intent intent) {
        return (intent == Intent.CHIT_CHAT) || !thinkingEnabled;
    }

    private String invoke(String prompt, Intent intent, boolean disableThinking, String scene) {
        Optional<RouteRule> rule = center.routeFor(intent);
        String primary = resolvePrimary(intent, rule);
        // 主备容灾：有路由规则则按其备链建 FailoverPolicy（maxRetries=备链长度，
        // 确保 FailoverExecutor 逐候选尝试：主失败/熔断→切备）；无规则则沿用快照级容灾或默认。
        FailoverPolicy failover;
        if (rule.isPresent()) {
            RouteRule r = rule.get();
            failover = FailoverPolicy.builder(r.id())
                    .fallbackModelIds(r.fallbackModelIds())
                    .maxRetries(r.fallbackModelIds().size())
                    .build();
        } else {
            failover = center.failover() != null
                    ? center.failover()
                    : new FailoverPolicy.Builder(primary).build();
        }
        // disableThinking 由调用方算定传入（chat/chatRaw 按意图驱动；decide 恒 true）。经 GatewayRequest→
        // FailoverExecutor→RoutingModelExecutor 透传到执行器，执行器据此合并 provider 各自关思考参数
        // （SF enable_thinking / SenseNova reasoning_effort）。
        GatewayRequest request = new GatewayRequest(primary, prompt, maxTokens,
                failover, center.flowControl(), disableThinking, scene);
        LlmResponse response = gateway.invoke(request);
        return response.content();
    }

    /** 解析主模型：路由规则优先，否则选择策略在启用模型中选。 */
    private String resolvePrimary(Intent intent, Optional<RouteRule> rule) {
        if (rule.isPresent() && rule.get().targetModelId() != null && !rule.get().targetModelId().isBlank()) {
            return rule.get().targetModelId();
        }
        List<ModelMetadata> candidates = rule.isPresent()
                ? center.registry().byTag(rule.get().routeType().name())
                : center.registry().enabled();
        if (candidates.isEmpty()) {
            throw new ModelSelectionException("无可用模型：intent=" + intent);
        }
        String tag = rule.map(r -> r.routeType().name()).orElse(null);
        return selector.select(candidates, SelectionCriteria.byTag(tag)).id();
    }
}
