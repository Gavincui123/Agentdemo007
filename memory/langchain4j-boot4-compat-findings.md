---
name: langchain4j-boot4-compat-findings
description: LC4j Boot 4.1 兼容核验+接入：可用，独立 boot4-starter 线；Jackson 2/3 共存非净；rerank 弱；AiServices 扩展点 seam 全覆盖韧性。已接入完成①LangChain4jModelExecutor(替HTTP,738测)②Slice1/2数据通路+GatewayChatModel(744)②Slice3 ToolCallExecutor数据步+退役手撸工具路径(710测GREEN+git安全网fa0c566/d036b81)；留②Slice4 ToolProvider按RoutePlan出子集
metadata: 
  node_type: memory
  type: reference
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-11T11:28:29.906Z
---

LC4j 在 Spring Boot 4.1 的接入核验（2026-09-10，tavily 实查 docs.langchain4j.dev 官方文档 + Maven Central + GitHub）：

**结论：可用，但非"净兼容"，按 [[phase4-gateway-design]] 的「引擎无关内核」seam（PipelineExecutor/ModelExecutor/EmbeddingService/Reranker）延后接入仍是当前最优解。**

- **Boot 4 官方支持**：docs.langchain4j.dev/tutorials/spring-boot-integration 原文——"Spring Boot 4 (4.0+) — use starters with the `-spring-boot4-starter` suffix"。即有**独立 boot4 starter 线**（非旧 `-spring-boot-starter` 的 Boot3 线）。模块命名 `langchain4j-{integration}-spring-boot4-starter`，如 `langchain4j-open-ai-spring-boot4-starter`。GitHub langchain4j-spring 仓库目录可见 boot4 线全覆盖（anthropic/mistral/ollama/open-ai/voyage-ai 等）。
- **版本（2026-09-10 用户 mvnrepository 代查钉正；之前 tavily 记的 1.20.0-beta30 是幻觉/错，Central 拉不到）**：**无 langchain4j-bom**（mvnrepository 查无 BOM，直接钉各 artifact 版本）；核心包 `langchain4j-core` **1.19.0 GA**；boot4-starter `langchain4j-open-ai-spring-boot4-starter` **1.19.0-beta29**（beta）。Java 17。旧 issue #4268（Boot3 线不兼容 Boot4）催生独立 boot4 线——boot4 线是修复。
- **OpenAI 模块 = SiliconFlow 可用**：SiliconFlow 是 OpenAI 兼容端点，`langchain4j-open-ai-spring-boot4-starter` 改 base-url 即接 SF；chat + embedding 都在 OpenAI 集成内（`langchain4j.open-ai.chat-model.*` / `langchain4j.open-ai.embedding-model.*` 属性）。
- **Jackson（关键坑）**：LC4j **1.x 核心仍用 Jackson 2**（`com.fasterxml.jackson`，mvnrepository 变更页见 `com.fasterxml.jackson.core»jackson-core/databind` 仍被引入）。**LC4j 2.0 正在迁 Jackson 3**（GitHub issue #4191 "2.0: Migrate to Jackson 3"）。但 Spring Boot 4 官方明确支持 **Jackson 2/3 共存**（spring.io/blog/2025/10/07: "can continue to use Jackson 2 or even use Jackson 2 and 3 at the same time"），所以不会崩——但把 LC4j 引入本工程（已全 Jackson 3：`tools.jackson.databind.*` + `JsonMapper.builder().build()`）会造成**双 Jackson 版本并存**于 classpath，不净。彻底对齐须等 LC4j 2.0 GA。
- **Rerank 弱**：LC4j 1.x 的 reranking 成熟度远不及 chat/embedding（无专用 SiliconFlow reranker 模块疑）。本工程 [[phase21-hybrid-retrieval-design]] 的 `SiliconFlowReranker`+`FailoverReranker`（经 `Reranker` seam）已自实且 693 测 GREEN——**不迁它进 LC4j**，保留 seam 即可。
- **与本工程 seam 对齐**：LC4j 的 ChatLanguageModel/EmbeddingModel/EmbeddingStore 抽象 ↔ 本工程 OpenAiModelExecutor/SiliconFlowEmbeddingService/InMemoryVectorStore。若日后接入，按 [[phase4-gateway-design]]「coexist-as-engine via PipelineExecutor seam」薄桥接，不替内核。

**采用判断（旧，作废）**：当前手搓服务已跑通（694 测 + 实跑 RAG 召回 size=4 已确认），LC4j 非"跑通"必需，是**可选的未来整合**。代价=双 Jackson（至 LC4j 2.0 GA）+ 多一层抽象。建议**仍延后**，待 LC4j 2.0 GA（原生 Jackson 3）再评估接入；接入也只桥接不替内核。

---

**更新（2026-09-10 后续，javap 核验主 artifact）：** 上面「延后+薄桥接」判断**作废**——根因是只看到 ChatModel/EmbeddingModel 抽象就停了，**漏了 AiServices 扩展点 seam 面**（即 [[dont-hardwrite-use-dep-methods]] 批评的「没查全 API 面」）。用户已主动接入（core 1.19.0 进 pom + calc tools 标 @Tool + 选 B）。

javap 主 artifact `langchain4j:1.19.0` 发现韧性挂点**全覆盖**（签名已核）：
- 工具熔断/审计→`tools(Map<ToolSpec,ToolExecutor>)` 注入自定义 ToolExecutor（装饰 `DefaultToolExecutor`）
- 自纠正→`ToolExecutionErrorHandler.handle(Throwable,ToolErrorContext)→ToolErrorHandlerResult(text)`（**Reparser 退役**）
- per-route 子集→`ToolProvider.provideTools(req)→ToolProviderResult`（`isDynamic()`，按请求出 spec→executor 子集）
- 模型主备/重试/关思考→`chatModel(ChatModel)` 接口注入自定义
- RAG→`retrievalAugmentor(RetrievalAugmentor)`；安全→`GuardrailService`+`Input/OutputGuardrail`（**SecurityAuditStep 退役**）
- 上下文→`systemMessageProviderWithContext(Function<InvocationContext,String>)`；流式→`streamingChatModel`+`TokenStream`；HITL→`BeforeToolExecution`；发明工具名→`HallucinatedToolNameStrategy` 原生护栏

**A/B 纠正**：旧 A（AiServices 当家丢韧性）/ B（手撸循环保韧性）= **假二选一**。正解 = **AiServices 当家 function-calling 循环，韧性以装饰器挂到原生 seam**（用依赖+保韧性不二选一）。**B 退役**。外层 pipeline（话术短路/RoutePlan 三层权威/per-stage 测评/HITL 子图/收口）留——编排层非循环，AiServices 替不了。

**最小首切片**：①加 `langchain4j:1.19.0` 主 artifact ②模型层只适配不重写（ChatLlmService 包成 ChatModel，暂不引 langchain4j-open-ai 替 HTTP——那要查官方文档+验型号，留后切片）③ToolExecutor 装饰器(熔断+audit+ToolExecutionErrorHandler)包 DefaultToolExecutor ④ToolProvider 按 RoutePlan.required_tools 出子集 ⑤退役 Detector/ParamParser/SchemaValidator/Reparser，calc-tool 集成测迁 AiServices 路径。关联 [[dont-hardwrite-use-dep-methods]] [[routeplan-design]] [[phase4-gateway-design]]。

---

**实测发现（2026-09-10，AiServicesToolLoopTest + AiServicesSelfCorrectionTest GREEN，脚本化假 ChatModel 驱动，无真实 LLM）：**

1. **DefaultToolExecutor 原生吞 @Tool 异常→异常消息当工具结果返回（不抛出）**。即 `@Tool` 方法抛 `ToolRecoverableException` 时，`DefaultToolExecutor.execute` 不向上抛，而是把异常 message 作为结果字符串返回→喂回模型→模型在 AiServices 循环里自己重发修正参数。**故 Reparser（LLM 重解析）/ToolErrorFeedback（异常→反馈 prompt）/executeCall 递归全部由默认行为退役，连 `ToolExecutionErrorHandler` 都不必设**（它是可选"定制反馈文本"seam，`.toolExecutionErrorHandler()`，默认路径不走它——`compensateOnToolErrors(true)` 也无济，handler 实测未被调）。比预想更省：自纠正开箱即用。
2. **breaker 语义迁移（须记，与手撸路径不同）**：`ResilientToolExecutor` 只在执行器**抛出**时 `recordFailure`；而 DefaultToolExecutor 把业务异常（坏参）吞成结果返回→ResilientToolExecutor 见成功返回→`recordSuccess`。故 LC4j 路径下 `ToolCircuitBreaker` 只对 **infra 失败**（执行器自身抛出：反射/类错误等）跳闸，**不对业务异常（坏参）跳闸**。本地 @Tool 无 HTTP，infra 失败罕见→熔断在 LC4j 路径主要防执行器级故障，不防业务坏参循环。手撸 `ToolExecutor` 原对 `ToolRecoverableException` 记失败——迁移后此语义消失，设计须知。
3. **Carrier/接口签名（javap 核实，零猜）**：`ChatModel` 接口全 default（覆写 `doChat(ChatRequest)→ChatResponse` 即薄委托）；`OpenAiChatModel implements ChatModel`（即 `GatewayChatModel implements ChatModel` 方向正确）；thinking 旋钮在 `OpenAiChatModel$OpenAiChatModelBuilder`：`reasoningEffort(String)`+`sendThinking(Boolean[,String])`+`returnThinking(Boolean)`+`customParameters(Map<String,Object>)`。**customParameters→请求体顶层字段（用户查官方文档确认，gate 已 lift）**——SiliconFlow `enable_thinking`(bool 默认true)/`thinking_budget`(int 128-32768 仅 enable_thinking=true 生效) 走此注入；另 `ChatModelListener.onRequest(ChatModelRequestContext)` 可 per-request 改 `((OpenAiChatRequestParameters)params).customParameters().put(...)`（**项目"意图驱动关思考"铁律的 seam**：闲聊/决策调用 enable_thinking=false，其他由 llm.thinking.enabled 开关定）；`ToolProvider.provideTools(ToolProviderRequest)` 的 `invocationParameters()` 是 RoutePlan 入参载体；`ChatResponse.aiMessage().toolExecutionRequests()` 取 tool_calls；`ToolExecutionResultMessage.text()` 取工具结果；`ToolSpecifications.toolSpecificationFrom(Method)` 生 schema（退役 SchemaValidator）。
4. **铁律②（最小可跑通版本优先）范式坐实**：没一上来写 GatewayChatModel+OpenAiChatModel+ToolProvider 整套大块，先一个脚本化假 ChatModel+真 DefaultToolExecutor+真 @Tool 跑通 seam（AiServicesToolLoopTest GREEN），再叠自纠正切片（AiServicesSelfCorrectionTest GREEN）。每步最小可跑通、GREEN 才往下——避免大块跑不起来返工。

**下一步（gated 待用户代取 3 份官方文档）**：叠真实 `OpenAiChatModel` provider（替 OpenAiModelExecutor-HTTP）——需 ①SiliconFlow 支持的 chat 模型型号清单 ②`enable_thinking` 关思考参数在 SF 的字段名/类型/取值（验 customParameters 透传）③LC4j OpenAI 模块接入官方文档（customHeaders/customParameters 透传语义 + tools 挂法）。拿到即叠。

---

**① 完成（2026-09-11，LangChain4jModelExecutor GREEN + 全量 738 测）**：

`LangChain4jModelExecutor implements ModelExecutor`（gateway/llm/）把 LlmRequest 翻译成 `OpenAiChatModel.chat(ChatRequest)→LlmResponse`，**退役 OpenAiModelExecutor-HTTP（类+测试已删）**。翻译契约镜像旧语义零漂移：modelId→modelName、prompt→UserMessage、maxTokens>0→`.maxTokens()`、disableThinking=true 且 provider 关思考参数非空→`customParameters` 注入（SF enable_thinking=false）、disableThinking=false 不注入（避非推理 400）、空 content/空 key→LlmUnavailableException、`tokenUsage.totalTokenCount()`→LlmResponse.tokens()（gateway 预算记账需真实 tokens）。

- **每请求 build OpenAiChatModel**（modelId+disableThinking 每请求变→model-level 设 maxTokens/customParameters，非 ChatRequest.maxOutputTokens）。功能正确，perf 可优化（缓存 model + `ChatModelListener.onRequest` per-request 改 customParameters 是 seam），留后。
- **LlmConfig.buildRoutingExecutor** 切到 LangChain4jModelExecutor + 显式 **120s 超时**（旧 RestTemplate 无超时=无限；llm.timeout 配置化留后，LlmProperties 暂无该字段）。**退役 RestTemplate+ObjectMapper bean**（llmRestTemplate/llmObjectMapper 删）——LLM 出站栈纯 LC4j 内置 Jackson2，不再依赖 Spring HTTP/Jackson3；RAG 层(embedding/reranker)有自家命名 bean 不受影响。
- 测试 seam：LangChain4jModelExecutor 构造器 **nullable HttpClientBuilder**（null→OpenAiChatModel 默认 JDK 客户端=真打 SF，经 OpenAiChatModelSiliconFlowSmokeTest 坐实；非空→假 transport 无网络恒 GREEN）。复用 OpenAiChatModelBodyCaptureTest 的 CapturingHttpClient 模式。
- 假 transport TDD（铁律②范式又证）：先 stub（返回空 LlmResponse，capturedBody 初始化""）→4 测全 RED（断言失败非 NPE）→实现→GREEN。
- RealLlmFailoverSmokeTest 改用 LangChain4jModelExecutor（真 LC4j HTTP）——env-gated(SF_KEY+DS_KEY)，需验型号(Qwen3.5-35B-A3B/deepseek-v4-flash) 才能跑通；该 smoke 的 provider 未设 disableThinkingParams→disableThinking=false→SF 思考（与旧同），1024 token 预算。
- RoutingModelExecutor javadoc `{@link}` 从 OpenAiModelExecutor 改 LangChain4jModelExecutor（leaf 可整替，引擎无关 seam 不变）。

**真路径坐实（2026-09-11，用户给 SF+DS 临时 key 跑 RealLlmFailoverSmokeTest）**：
- `primaryBrokenKey` GREEN——主坏 key→SF `code 30014 "Token is invalid"`→FailoverExecutor 自动切备→SenseNova `deepseek-v4-flash`（真 DS key）→content「你好！」tokens=133。**主备容灾全链路经 ① wiring 真打真通**。
- 跑烟暴露既存 bug：RoutingModelExecutor 把 leaf 回传的 raw 模型串透传给调用方，违其 javadoc 规格「不渗入 raw 串」。1 行修：路由层把响应 modelId 回映射为合成 id（raw↔composite 边界归路由层，不归每个 executor；leaf 返 raw、router 出 composite）。已核零下游风险（FailoverExecutor 透传不读/CircuitBreakingModelExecutor 按 request.modelId 记熔断=合成 id/ChatLlmService 只用 content+tokens）。全量 739 GREEN。
- `primarySiliconflow` 之前 flaky（思考开+1024 预算空 content→切备）；**已解**：冒烟改 6 参 `GatewayRequest` 带 `disableThinking=true` + 两 provider 都设 `enable_thinking=false` 关思考 → 主备都稳出 content（镜像 prod 闲聊/决策路径，2.59s 两测 GREEN）。**通用范式**：任何推理模型冒烟都应关思考避预算 flakiness。
- **SenseNova→Aliyun 备用替换（2026-09-11）**：用户给阿里云 DashScope（OpenAI 兼容 `/compatible-mode/v1`）临时 key + `qwen3.8-max`/`deepseek-v4-pro-0813`。经 ① `LangChain4jModelExecutor` 接通——**纯配置替换零新接入口**（引擎无关 seam 回报：执行器只认 baseUrl/apiKey/disableThinkingParams，OpenAI 兼容端点即接）。`primaryBrokenKey` GREEN：主坏 key→SF `30014 Token is invalid`→切 Aliyun `qwen3.8-max` 关思考→content「你好，很高兴遇见你！」tokens=21。冒烟 env 变量 `DS_KEY`→`ALIYUN_KEY`；`application.yml` 注释模板 + `LlmProperties` javadoc 同步 SenseNova→Aliyun（关思考参数说明改为「不同 provider 可能不同」泛化）。单测（LlmPropertiesModelConfigSource/LlmConfigReporter/RoutingModelExecutor/ModelCircuitBreaker）的 SenseNova 测试数据保留——测逻辑非 provider，无需改。全量 739 GREEN。
- ⚠️ 三把临时 key（SF `sk-npbdmjsk…` + SenseNova `sk-go8qvPcu…` [旧备，已替] + Aliyun `sk-c1d029b…`）均已在对话记录中，测完须轮换/吊销。

**② 完成（2026-09-11，Slice 1+2 GREEN，全量 744 测）**：GatewayChatModel 作 AiServices 真 ChatModel 驱动 function-calling 循环，调模型这一步经网关收口（韧性不二选一全复用）。

**Slice 1（数据通路·tools-IN/tool_calls-OUT 过执行器）**：
- `LlmRequest` 扩 `List<ChatMessage> messages` + `List<ToolSpecification> tools`（canonical 6-arg + 工具路径 5-arg[messages+tools,prompt=null] + plain-chat 4/3-arg compat）。
- `LlmResponse` 扩 `List<ToolExecutionRequest> toolCalls`（canonical 4-arg + 3-arg compat）。
- `LangChain4jModelExecutor.execute` 工具感知：messages 非空→`chatReqB.messages(request.messages())`（工具路径），否则→`new UserMessage(prompt)`（plain-chat）；tools 非空→`.toolSpecifications(request.tools())`；提取 `resp.aiMessage().toolExecutionRequests()`；空 content **且**无 tool_calls 才抛 LlmUnavailable（有 tool_calls 即使 content 空也有效产出）；return 4-arg 带 toolCalls。**plain-chat 零漂移**（4 旧测全 GREEN）。
- `RoutingModelExecutor`：forward 用 6-arg LlmRequest（透传 messages/tools）；回映射 4-arg LlmResponse（透传 toolCalls）。raw↔composite 边界归路由层不变。
- 测：`LangChain4jModelExecutorTest` 第 5 测（罐装 tool_calls 响应→执行器提取 toolCalls + 请求体含 tools/messages 不含 prompt）。**坑**：罐装 OpenAI tool_calls JSON 须 `finish_reason` 在 message **外**（choice 内、message 兄弟）+ choice `{` 须闭合——原 JSON 把 finish_reason 塞进 message 且漏 choice-close `}`→Jackson「Unexpected close marker ]: expected }」解析错。

**Slice 2a（网关层透传 messages/tools）**：
- `GatewayRequest` 扩 `messages` + `tools`（mirror LlmRequest；canonical 8-arg + 工具路径 7-arg + plain-chat 5/6-arg compat；5/6-arg 旧调用方 ChatLlmService:125 + 8 处测试全兼容，二进制不破）。
- `FailoverExecutor.execute` L86-87 forward 从 4-arg plain-chat ctor 改 6-arg canonical（透传 request.messages()/tools()）——plain-chat 仍空 List 不影响（执行器回退 prompt）。
- 测：`GatewayToolPathTest`（gateway.invoke 工具路径 GatewayRequest → stub 执行器捕获 LlmRequest 带 messages+tools）。TDD RED（forward 未做→stub 见空 messages）→GREEN。

**Slice 2b（GatewayChatModel implements ChatModel）**：`gateway/llm/GatewayChatModel.java` implements `dev.langchain4j.model.chat.ChatModel`，覆 `doChat(ChatRequest)`。配置态（构造期固定）：modelId/maxTokens/failover/flow/disableThinking；每轮只 messages/tools 来自 ChatRequest。IN 翻译：`chatRequest.messages()/toolSpecifications()` → 工具路径 GatewayRequest → `gateway.invoke`（预算关卡→failover/熔断/关思考→执行器→记账）。OUT 翻译：LlmResponse.toolCalls 非空→`AiMessage.builder().toolExecutionRequests(resp.toolCalls())` + `FinishReason.TOOL_EXECUTION`（content 可并存则 .text()）；否则→`.text(content)` + `STOP`；`tokenUsage(new TokenUsage(resp.tokens()))`。javap 核实：ChatRequest.messages()/toolSpecifications() 返 List；AiMessage.Builder.text(String)/toolExecutionRequests(List)；ChatResponse.Builder.aiMessage/tokenUsage/finishReason/build。
- 测：`GatewayChatModelTest`（stub 执行器返罐装 toolCalls LlmResponse→doChat→ChatResponse.aiMessage().toolExecutionRequests() + tokens + TOOL_EXECUTION；纯文本→STOP）。

**Slice 2c（集成收口·AiServices 经网关驱动循环）**：`GatewayChatModelAiServicesLoopTest` 镜像 `AiServicesToolLoopTest` 但 ScriptedChatModel→网关背书 GatewayChatModel（脚本化执行器 call1→tool_call/call2→终答经真网关栈）。终答含真 @Tool 算的「6」=全数据流经网关闭环（GatewayChatModel 双向翻译 + AiServices 循环 + DefaultToolExecutor 派发 + ResilientToolExecutor 韧性装饰 + 真 @Tool）。exec.calls()=2（循环经网关跑满 2 轮）/execCount=1/breaker CLOSED。

**② Slice 3 完成（2026-09-11，装配+退役，全量 710 测 GREEN）**：option A 数据步单次前向（**非** AiServices 自驱动循环——后者留为已测"agent 模式"能力，非 prod 数据步路径）。pipeline 尾零改（ContextBuilder+终答步不动，[[degradation-and-eval-principles]]：收口最稳）。

- **ToolCallExecutor**（capability/tool/，替手撸 ToolExecutor 角色）：protected `buildChatModel()` seam（prod 按 CHIT_CHAT 路由 per-call 解析 RouteRule→FailoverPolicy→flowControl→GatewayChatModel[小模型+disableThinking=true，工具探测=决策调用，镜像 ChatLlmService.decide]；测试覆写为 scripted）。`execute(query)→List<String>`：doChat(messages=[query],tools=specs)→模型出 tool_calls→executors.get(name) 派发（幻觉工具名跳过）→结果列表。无 CHAT_CHAT 路由（dev/未装配）→null→返空 List.of()（每步降级不阻塞主链路）。breaker OPEN 透传 ToolCircuitOpenException 交 ToolExecutionStep 收口。测 ToolCallExecutorTest 4 测（tool_call→["6"]+CLOSED / no-tool→[] / breaker-OPEN→抛 / 无路由→[]）。
- **ToolSchemaProvider**（单源 schema+executor）：同批 @Tool beans 反射（`ClassUtils.getUserClass(bean).getDeclaredMethods()`+`isAnnotationPresent(Tool.class)`+`toolSpecificationFrom(method)`）→ ToolBinding(spec,bean,method) → allSchemas/schemasFor + executors(breaker)→ResilientToolExecutor 包 DefaultToolExecutor。**键一致**（同源 binding，避免模型调到无 executor 的死工具）。per-route 子集 schemasFor 按 RoutePlan.required_tools（Slice 4 地基）。测 ToolSchemaProviderTest 4 测（4 工具名/键一致/子集过滤/null 空）。
- **ToolConfig** 装配 3 bean：toolCircuitBreaker(@Value 阈值/冷却)+toolSchemaProvider(4 @Tool beans: triangle/circle/multiplication/arithmetic)+toolCallExecutor(gateway/center/schemas/breaker/maxTokens)。退役旧 toolExecutor/toolErrorFeedback/reparser/4 ToolDefinition bean。
- **ToolExecutionStep** 重接：field `ToolExecutor`→`ToolCallExecutor`；process: execute(query)→非空 setToolResults+recordTool(true)+Proceed；catch ToolCircuitOpenException→ShortCircuit(TOOL_FAILURE)（指标 try-catch 防反噬）。**删 ToolRecoverableException catch**（死代码——DefaultToolExecutor 原生吞 @Tool 异常不透传）。
- **ArithmeticTool 迁 LC4j @Tool**（非 retire）：本地 `@Tool(name="arithmetic",...)`→LC4j `@Tool("...")`（spec name=方法名"calculate"，LC4j @Tool 无 name 属性）。并入 toolSchemaProvider（第 4 工具）。保留 ArithmeticEvaluator（有用计算逻辑，非手撸管线）。退役 ArithmeticDetector（关键词检测）+ 本地 Tool 注解。ArithmeticToolTest 删 toolAnnotation 元数据测（@Tool 拾起改由 ToolSchemaProviderTest 验）。
- **退役删除（15 prod + 7 测，745→710）**：ToolExecutor/ToolDefinition/ParamSpec/ToolCall/ParamParser/SchemaValidator/ToolRegistry/Reparser/ValidationResult(tool 版 valid()/invalid())/4 Detectors/本地 Tool 注解/ToolErrorFeedback(+ToolFeedbackOutcome) + ToolExecutorTest/CircuitTest/ParamParserTest/SchemaValidatorTest/ToolRegistryTest/ArithmeticDetectorTest/ToolErrorFeedbackTest。
- **git 安全网**（项目非 git 仓库→删除不可逆，用户定先 git init）：fa0c566 GREEN 基线(745)+d036b81 删除 commit(710)。删除可 `git revert d036b81` 整体撤销、单文件 `git restore <file>` 恢复。
- **坑**：①codegraph 对 `ToolExecutor` blast-radius **同名碰撞误报**——ToolCallExecutor/ToolSchemaProvider/ResilientToolExecutor 都 import LC4j `dev.langchain4j.service.tool.ToolExecutor`（单类型 import 优先于同包类，已编译证实），非手撸那个；验引用须 grep 非 codegraph。②**两个 ValidationResult**：tool 版(valid()/invalid()，随 SchemaValidator 删)+output 版(ok()/fail()，JsonSchemaValidator 用，留)——方法名不同是两个独立类，别误删 output 版。③calc-tool 测迁"直接测 @Tool 方法"非"走 ToolCallExecutor 路径"（后者已由 ToolCallExecutorTest 4 测覆盖 triangle 全场景；circle/multiplication 是 LC4j 反射的参数数变体，重测框架非本仓代码，YAGNI）。④ToolCircuitWiringTest 从手撸接线 @SpringBootTest 改成新装配 @SpringBootTest 烟测（4 bean 接线+4 @Tool 拾起，17s 全上下文加载证 ToolConfig 自洽）。⑤TDD RED 阶段被 Bash 分类器限流客观阻断（ToolCallExecutorTest 未看过失败），但 4 断言均有区分力非恒通过，GREEN 有意义。

**下一步（② Slice 4，留）**：ToolProvider 按 RoutePlan.required_tools 出子集（`provideTools(ToolProviderRequest).invocationParameters()` 作 RoutePlan 载波；ToolSchemaProvider.schemasFor 已备地基）——依赖 [[routeplan-design]] 子系统（task #132-137）。关联 [[dont-hardwrite-use-dep-methods]] [[phase4-gateway-design]] [[phase-llm-primary-backup-breaker]] [[degradation-and-eval-principles]]。
