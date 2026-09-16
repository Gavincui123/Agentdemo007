package com.agentdemo007.capability;

import com.agentdemo007.capability.business.MockPolicyQueryService;
import com.agentdemo007.capability.business.OrderQueryService;
import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyFragment;
import com.agentdemo007.capability.business.ProductQueryService;
import com.agentdemo007.capability.business.UserQueryService;
import com.agentdemo007.capability.plan.RouteCandidateParser;
import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.plan.RoutePlanBaselines;
import com.agentdemo007.capability.plan.RoutePlanCandidate;
import com.agentdemo007.capability.plan.RoutePlanContractValidator;
import com.agentdemo007.capability.plan.RoutePlanRuleMatcher;
import com.agentdemo007.capability.plan.RoutePromptBuilder;
import com.agentdemo007.capability.tool.OrderQueryTool;
import com.agentdemo007.capability.tool.ProductQueryTool;
import com.agentdemo007.capability.tool.PromotionPolicyTool;
import com.agentdemo007.capability.tool.RefundPolicyTool;
import com.agentdemo007.capability.tool.ReturnPolicyTool;
import com.agentdemo007.capability.tool.ToolCallResult;
import com.agentdemo007.capability.tool.ToolCategory;
import com.agentdemo007.capability.tool.ToolSchemaProvider;
import com.agentdemo007.capability.tool.UserQueryTool;
import com.agentdemo007.capability.workflow.AfterSaleSubmitService;
import com.agentdemo007.capability.workflow.AfterSaleWorkflowGraph;
import com.agentdemo007.capability.workflow.AfterSaleWorkflowOutcome;
import com.agentdemo007.capability.workflow.Reason;
import com.agentdemo007.capability.workflow.RefundValidationRule;
import com.agentdemo007.capability.workflow.WorkflowApprovalDecision;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4 组真 SiliconFlow 冒烟（[[business-tools-workflow-dag]] §2.5·env-gated）。
 *
 * <p><b>用户钦定</b>："现在提出的工具调用都要基于真 SiliconFlow 去进行测试，否则工具调用就失去真正意义"——
 * T1-T3 全走真 SF function-calling（非 ScriptedModelExecutor），验真模型能调业务 @Tool；T4 走真 SF 路由
 * （用户选定"加真 SF 路由 RoutePlan"——图内无 LLM，real SF 落在路由识别 refund/return + requiresWorkflow）。
 *
 * <p><b>运行方式</b>（密钥经环境变量注入，不落明文——[[phase-llm-primary-backup-breaker]] 铁律）：
 * <pre>
 * export SF_KEY=sk-你的SiliconFlow密钥
 * # SF_MODEL=可选，默认 Qwen/Qwen3-14B（T1-T4 统一；支持 function-calling + enable_thinking）
 * ./mvnw -Dtest=BusinessToolsSiliconFlowSmokeTest test
 * </pre>
 * 无 SF_KEY 时自动跳过（{@link Assumptions#assumeTrue}），不报错（与 {@link com.agentdemo007.gateway.llm.OpenAiChatModelSiliconFlowSmokeTest} 同模式）。
 *
 * <p><b>断言粒度</b>（真模型非确定→断言结构性属性非精确串）：tool_calls 真发出 + 真 {@link ToolExecutor}
 * 执行 + category 通道正确（T1-T3）；路由候选可解析 + 护栏收敛 requiresWorkflow=true（T4）。确定性图收口
 * （T4 图 invoke）验真 SF 路由结果落到 {@link AfterSaleWorkflowGraph} 终态。
 *
 * <p><b>真跑坑①（2026-09-12·ClassCast，已修）</b>：{@code OpenAiChatModel.doChat} 把 {@link ChatRequest} 的 parameters
 * 强转 {@link OpenAiChatRequestParameters}——故 toolSpec 须<b>放 parameters 内</b>（{@code OpenAiChatRequestParameters
 * .builder().toolSpecifications(specs)}，继承自 {@code DefaultChatRequestParameters.Builder}），不能放
 * {@code ChatRequest.builder().toolSpecifications(...)}（后者产 {@code DefaultChatRequestParameters}→ClassCast）。
 *
 * <p><b>真跑坑②（2026-09-12·per-request params 吞 model 字段→20015，已修）</b>：坑①修后（toolSpec 放 per-request
 * {@link OpenAiChatRequestParameters} 内），T1-T3 真打仍报 {@code 20015 "The parameter is invalid"}。初判"Qwen3-14B
 * 不支持 FC、改用 GLM-5.1"——<b>用户纠正 + 官方 API 手册确认 Qwen3-14B 支持 tools</b>（{@code tools} 为通用 OpenAI
 * 兼容参；{@code min_p} 注"仅适用于 Qwen3"证 Qwen3 为支持系列）。经 {@code OpenAiChatModelBodyCaptureTest} 假 transport
 * 捕 body 坐实真因：<b>per-request params 覆盖 model 的 defaultRequestParameters，未带 {@code modelName} 致 body 缺
 * {@code "model"} 字段</b>（SF {@code model} 为 required→20015）。修：per-request params 显式
 * {@code .modelName(modelName)}（坑① ClassCast 修复带入的副作用，坑②补齐）。T1-T4 统一 Qwen3-14B（不分立模型）。
 *
 * <p><b>关思考铁律（[[phase-llm-primary-backup-breaker]]）</b>：T1-T4 全经 {@code customParameters(enable_thinking=false)}
 * 关思考（工具探测/路由=决策调用；Qwen3-14B 支持该参，纯 chat 已实测 GREEN，带 tools 亦合法——坑②修后 body 含
 * model+enable_thinking+tools 三者齐全）。镜像 {@link com.agentdemo007.capability.tool.ToolCallExecutor#buildChatModel}。
 */
class BusinessToolsSiliconFlowSmokeTest {

    private static final String SF_BASE = "https://api.siliconflow.cn/v1";
    private static final String DEFAULT_MODEL = "Qwen/Qwen3-14B"; // T1-T4 统一（支持 FC + enable_thinking，用户+官方 API 手册确认）

    private static void assumeSfKey() {
        String key = System.getenv("SF_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "SF_KEY 未设置——跳过真 SiliconFlow 冒烟（设置后验真模型调业务工具/路由）");
    }

    private static String sfModel() {
        String m = System.getenv("SF_MODEL");
        return (m == null || m.isBlank()) ? DEFAULT_MODEL : m;
    }

    /**
     * 真 SF {@link OpenAiChatModel}（T1-T4 统一）：{@code Qwen/Qwen3-14B} + model 级 {@code enable_thinking=false}。
     * T4 路由经 {@code .chat(String)} 纯文本 JSON 输出（用 defaultRequestParameters，含 model 名）；T1-T3 工具调用
     * 经 {@link #dispatchTools} 的 per-request parameters（须显式带 {@code modelName}，见真跑坑②——否则 body 缺 model）。
     */
    private static OpenAiChatModel sfChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(SF_BASE)
                .apiKey(System.getenv("SF_KEY"))
                .modelName(sfModel())
                .timeout(Duration.ofSeconds(90)) // SF 推理可能慢，宽放
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .customParameters(Map.of("enable_thinking", false)) // 关思考（决策调用铁律）
                        .build())
                .build();
    }

    /** 6 业务 @Tool 单源（3 RUNTIME 外部系统 + 3 RAG 政策），镜像 {@link com.agentdemo007.capability.tool.ToolConfig} 装配。 */
    private static ToolSchemaProvider schemas() {
        OrderQueryService orderService = new OrderQueryService();
        UserQueryService userService = new UserQueryService();
        ProductQueryService productService = new ProductQueryService();
        MockPolicyQueryService policyService = new MockPolicyQueryService();
        return new ToolSchemaProvider(List.of(
                new OrderQueryTool(orderService), new UserQueryTool(userService), new ProductQueryTool(productService),
                new ReturnPolicyTool(policyService), new RefundPolicyTool(policyService), new PromotionPolicyTool(policyService)));
    }

    private static ToolCircuitBreaker breaker() {
        return new ToolCircuitBreaker(3, 30000, System::currentTimeMillis);
    }

    /**
     * 真模型单轮前向 + 真 {@link ToolExecutor} 执行 tool_calls + 按 categoryMap 标通道——镜像
     * {@link com.agentdemo007.capability.tool.ToolCallExecutor#execute}，但直用真 SF {@link OpenAiChatModel}
     * （不经网关，冒烟聚焦"真模型能否调工具"语义，路由/容灾逻辑由既有单测覆盖）。
     *
     * <p>{@link OpenAiChatRequestParameters} 经 builder 带 {@code modelName}+{@code customParameters(enable_thinking=false)}
     * +{@code toolSpecifications}（均继承自 {@code DefaultChatRequestParameters.Builder}），作 {@link ChatRequest#parameters()}
     * 传入——避 ClassCast（真跑坑①）<b>且</b>补 {@code modelName} 防 body 缺 model 字段（真跑坑②：per-request params
     * 覆盖 defaultRequestParameters，不带 modelName 则 model 名丢失→SF 20015）。{@code OpenAiChatModelBodyCaptureTest}
     * 假 transport 捕 body 坐实：不带 modelName→body 无 "model"；带→有。
     */
    private List<ToolCallResult> dispatchTools(String modelName, OpenAiChatModel model, ToolSchemaProvider schemas, String query) {
        OpenAiChatRequestParameters params = OpenAiChatRequestParameters.builder()
                .modelName(modelName) // 真跑坑②：per-request params 须显式带，否则 body 缺 model→SF 20015
                .customParameters(Map.of("enable_thinking", false)) // 关思考（决策调用铁律）
                .toolSpecifications(schemas.allSchemas())
                .build();
        ChatRequest req = ChatRequest.builder()
                .messages(new UserMessage(query))
                .parameters(params)
                .build();
        ChatResponse resp = model.doChat(req);
        List<ToolExecutionRequest> calls = resp.aiMessage().toolExecutionRequests();
        Map<String, ToolExecutor> executors = schemas.executors(breaker());
        Map<String, ToolCategory> cats = schemas.categoryMap();
        List<ToolCallResult> results = new ArrayList<>();
        if (calls != null) {
            for (ToolExecutionRequest call : calls) {
                ToolExecutor exec = executors.get(call.name());
                if (exec == null) {
                    continue; // 模型幻觉工具名：跳过（同 ToolCallExecutor 降级）
                }
                String content = exec.execute(call, null); // 真 DefaultToolExecutor 反射调真 @Tool
                results.add(new ToolCallResult(call.name(), content, cats.getOrDefault(call.name(), ToolCategory.COMPUTE)));
            }
        }
        return results;
    }

    // ---- T1 实时事实：真模型调订单/用户/商品工具 → RUNTIME 通道 ----

    @Test
    void t1_realSf_orderQuery_routesToRuntimeChannel() {
        assumeSfKey();
        ToolSchemaProvider s = schemas();
        List<ToolCallResult> results = dispatchTools(sfModel(), sfChatModel(), s,
                "请使用工具帮我查询订单 ORD-001 的状态、商品和金额");

        System.out.println("[T1] SF[" + sfModel() + "] results=" + results);
        // 真模型至少调一个工具 + 至少一个 RUNTIME 通道（订单/用户/商品=外部系统高置信事实）
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(r -> r.category() == ToolCategory.RUNTIME);
        assertThat(results).allSatisfy(r -> assertThat(r.content()).isNotBlank());
    }

    // ---- T2 RAG 政策：真模型调退货/退款政策工具 → RAG 通道带 citation ----

    @Test
    void t2_realSf_policyQuery_routesToRagChannelWithCitation() {
        assumeSfKey();
        ToolSchemaProvider s = schemas();
        List<ToolCallResult> results = dispatchTools(sfModel(), sfChatModel(), s, "请使用工具查询退货政策是什么");

        System.out.println("[T2] SF[" + sfModel() + "] results=" + results);
        assertThat(results).isNotEmpty();
        // RAG 通道工具返 PolicyFragment JSON {text,source}；验可解析 + text/source 非空（citation）
        List<ToolCallResult> ragResults = results.stream().filter(r -> r.category() == ToolCategory.RAG).toList();
        assertThat(ragResults).isNotEmpty();
        Optional<PolicyFragment> parsed = ragResults.stream()
                .map(r -> PolicyFragment.fromJson(r.content()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        assertThat(parsed).as("RAG 工具结果须为可解析的 PolicyFragment JSON 带 citation").isPresent();
        assertThat(parsed.get().text()).isNotBlank();
        assertThat(parsed.get().source()).as("知识库数据须带来源 citation").isNotBlank();
    }

    // ---- T3 商品推荐：真模型一轮回放多 tool_calls（P1 顺序执行）→ 三通道各有结果 ----

    @Test
    void t3_realSf_productRecommendation_emitsMultipleToolCalls() {
        assumeSfKey();
        ToolSchemaProvider s = schemas();
        // 多工具需求 prompt：商品推荐（RUNTIME）+ 会员活动政策（RAG）→ 模型一轮 emit ≥2 tool_calls
        List<ToolCallResult> results = dispatchTools(sfModel(), sfChatModel(), s,
                "我想给朋友买个礼物，请用工具同时查一下有什么商品推荐以及当前的会员活动政策");

        System.out.println("[T3] SF[" + sfModel() + "] results=" + results);
        // P1：模型一轮回放多 tool_calls（"并行"在模型调用层成立，顺序执行不改语义）
        assertThat(results).as("商品推荐场景须触发 ≥2 个 tool_calls（商品+活动政策）").hasSizeGreaterThanOrEqualTo(2);
        // 三通道路由：至少一个 RUNTIME（商品）+ 至少一个 RAG（活动政策）
        assertThat(results).anyMatch(r -> r.category() == ToolCategory.RUNTIME);
        assertThat(results).anyMatch(r -> r.category() == ToolCategory.RAG);
    }

    // ---- T4 退货/退款走 Workflow：真 SF 路由识别 refund + 护栏收敛 requiresWorkflow → 确定性图收口 ----

    /**
     * 真 SF 路由（用户选定范围）：RoutePromptBuilder 预构 8 字段规格 prompt → 真模型输出 JSON 候选 →
     * RouteCandidateParser 解析。真模型可能<b>不自纠约束</b>（实测 Qwen3-14B 把"我要退款"的
     * {@code requires_workflow} 误置 false / risk medium / fallback tool_first——违 refund_request 基线），
     * 故<b>不</b>断言原始候选的 requires_workflow；改经 {@link RoutePlanRuleMatcher#converge} 护栏收敛
     * （workflow_boundary/risk_floor 违例兜底到 refund_request 基线：requires_workflow=true/high/workflow_first），
     * 断言<b>收敛后</b> routePlan 的护栏矫正力。
     *
     * <p><b>已知限制（真跑发现，须告知用户）</b>：模型违例时 converge 兜底 source=DETERMINISTIC_FALLBACK，
     * 而 {@code WorkflowExecutionStep@670} 的 #135 source 门控（仅 LLM_WITH_POLICY_CONSTRAINTS 触发工作流）
     * 会<b>跳过</b>工作流走主链降级——故工作流<b>端到端触发</b>须模型有效产出 requires_workflow=true（LLM source），
     * 当前 Qwen3-14B 不可靠如此。本测验"真 SF 识别 refund 意图 + 护栏收敛 + 确定性图收口"三层，非端到端触发。
     * 改进方向：强化 route prompt（显式"退款/退货 必须 requires_workflow=true"）或换约束遵循更强的 route 模型。
     */
    @Test
    void t4_realSf_routesRefundToWorkflow_convergedToWorkflow() {
        assumeSfKey();
        RoutePromptBuilder promptBuilder = new RoutePromptBuilder(new RoutePlanBaselines());
        String prompt = promptBuilder.build(null, "我要退款 ORD-003");
        OpenAiChatModel model = sfChatModel();
        String raw = model.chat(prompt); // 路由=纯文本 JSON 输出（无 tools）

        System.out.println("[T4] SF[" + sfModel() + "] route raw=" + raw);
        RouteCandidateParser parser = RouteCandidateParser.create();
        Optional<RoutePlanCandidate> rawCandidate = parser.parse(raw);

        // 真 SF 路由贡献：须产出可解析候选 + 识别为 refund_request（退款动作意图，非状态查询）
        assertThat(rawCandidate).as("真 SF 须产出可解析的路由候选 JSON").isPresent();
        assertThat(rawCandidate.get().intent())
                .as("真 SF 须识别退款请求为 refund_request（非 refund_status_query）")
                .isEqualTo("refund_request");

        // 护栏收敛：RoutePlanRuleMatcher.converge 矫正模型违例（requires_workflow 误置 false / risk medium /
        // fallback tool_first → workflow_boundary + risk_floor 违例 → 兜底 refund_request 基线）
        RoutePlanRuleMatcher matcher = new RoutePlanRuleMatcher(new RoutePlanContractValidator(), new RoutePlanBaselines());
        RoutePlan converged = matcher.converge(rawCandidate.get());
        System.out.println("[T4] converged: intent=" + converged.intent()
                + " requiresWorkflow=" + converged.requiresWorkflow()
                + " risk=" + converged.riskLevel()
                + " fallback=" + converged.fallbackPolicy()
                + " source=" + converged.source()
                + " policyConstraints=" + converged.policyConstraints());
        assertThat(converged.intent()).isEqualTo("refund_request");
        assertThat(converged.requiresWorkflow()).as("护栏须把退款收敛为 requires_workflow=true").isTrue();
        assertThat(converged.riskLevel()).isEqualTo(RoutePlanCandidate.RiskLevel.HIGH);
        assertThat(converged.fallbackPolicy()).isEqualTo(RoutePlanCandidate.FallbackPolicy.WORKFLOW_FIRST);

        // 确定性图收口（图内无 LLM——real SF 落在路由）：退款工作流跑 → ORD-003（10010 的单）≠ 当前 10086
        // → Rejected(ORDER_NOT_OWNED) → presetReply 短路（Slice 4 收口）
        AfterSaleWorkflowGraph refundGraph = new AfterSaleWorkflowGraph(
                new UserQueryService(), new OrderQueryService(), new MockPolicyQueryService(),
                PolicyDomain.REFUND,
                new RefundValidationRule(Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC)),
                (AfterSaleSubmitService) ctx -> "WF-SMOKE",
                (WorkflowApprovalDecision) wr -> new WorkflowApprovalDecision.Approved("auto"),
                "10086");
        AfterSaleWorkflowOutcome outcome = refundGraph.invoke(new PipelineContext("smoke", "退款 ORD-003"));
        assertThat(outcome).isInstanceOf(AfterSaleWorkflowOutcome.Rejected.class);
        assertThat(((AfterSaleWorkflowOutcome.Rejected) outcome).reason()).isEqualTo(Reason.ORDER_NOT_OWNED);
    }
}
