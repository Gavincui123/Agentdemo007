---
name: phase-llm-primary-backup-breaker
description: T6-T10 LLM 主备容灾+模型级熔断已实现（694 测 GREEN，2 smoke gated）；Part A 意图驱动关思考已并入（disableThinking 每请求由 intent+llm.thinking.enabled 算定透传）；Part B max_tokens 旋钮（llm.max-tokens 默认1024→GatewayRequest→LlmRequest 透传，给推理模型空content留预算）；推理模型空 content→抛 LlmUnavailable 计熔断+切备
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-10T03:41:57.891Z
---

T6-T10 LLM 主备容灾 + per-model 滑动窗口熔断 + 按provider关思考 **已实现**（663 测 GREEN，2 真实冒烟 gated 跳过）。在 [[phase4-gateway-design]] 内核 + [[phase5-resilience-design]] 韧性层之上接线真实多 provider。

**关键设计：**
- `LlmProperties`(`@ConfigurationProperties(prefix="llm")`)：`enabled`/`providers[*]`(id,base-url,api-key,large/small-model)/`circuit-breaker`(window-ms=60000,failure-threshold=5,cooldown-ms=30000)。密钥只经 `${}` 环境变量注入、不落明文（用户铁律）。
- `LlmPropertiesModelConfigSource`：primary=providers[0]（打 RouteType 标签 REASONING/LONG_CONTEXT/STRUCTURED 等），backups[1..] 不打标签仅经 fallback 可达；5 RouteRules（CHIT_CHAT→primary-small[smallFallback]，REASONING/LONG_CONTEXT/STRUCTURED/OTHER→primary-large[largeFallback]）。snapshot 的 flowControl/failover 留 null——按意图 fallback 经 RouteRule 而非全局。
- **属性门控互斥**（非 `@ConditionalOnMissingBean` 顺序脆弱）：`LlmConfig` `@ConditionalOnProperty(llm.enabled=true)` 装真执行器/源；`GatewayConfig` noop 执行器/noop 源 `havingValue=false,matchIfMissing=true`。装配顺序无关、可调试。
- **modelId 解耦**：合成 id `{providerId}-large/-small` = 注册表 id = 熔断 key = routes-map key（全同构），与发往 API 的 raw 模型串（如 `Qwen/Qwen3.5-35B-A3B`）解耦——`RoutingModelExecutor` 翻译。
- `ChatLlmService.invoke` 按意图取 `RouteRule.fallbackModelIds` 建 `FailoverPolicy(maxRetries=fallbackModelIds.size())`。**坑**：`FailoverExecutor` 默认 `maxRetries=0`→备链永不试；须显式设 `maxRetries=fallbackModelIds.size()` 才试遍候选。`candidates=[primary]+fallbackModelIds`，`maxAttempts=min(maxRetries+1, candidates.size)`。AUDIT_AND_FAIL/FEEDBACK_TO_LLM 异常传播不转移，其余切备。
- `CircuitBreakingModelExecutor` 装饰 `RoutingModelExecutor`，位于 FailoverExecutor 重试循环**内**（同模型每次失败调用计 1）；OPEN→抛 `CircuitOpenException`→分诊 NON_RETRYABLE_CLIENT→切备（与既有容灾同路）。per-model 隔离：主熔断不阻断向备切换。
- **推理模型空 content = 模型失败**（真冒烟发现）：Qwen3.5 等 reasoning 模型在 max_tokens 不足时 `reasoning_content` 耗尽预算→`content:""`、`finish_reason:"length"`。`OpenAiModelExecutor` 检测空 content→抛 `LlmUnavailableException`（分诊 NON_RETRYABLE_CLIENT/FAIL）→**计熔断 + 故障转移切备**，而非把空串当成功回复直达用户（违反 每步降级/统一收口）。**不**回退读 `reasoning_content`（思考过程非最终答案，外泄污染回复）。TDD：`OpenAiModelExecutorTest#blankContent_throwsLlmUnavailable_reasoningTruncation`。
- `ModelConfigBootstrap`(`@Component CommandLineRunner`) 启动调 `center.refresh()` 自动从 wired ModelConfigSource 落地注册表；refresh 失败不阻塞启动（registry 空→MODEL_DOWN）。
- **按 provider 关思考**（Tavily 实查官方文档后实现）：`OpenAiModelExecutor` 6-arg ctor 接 `boolean disableThinking`+`Map<String,Object> thinkingOffParams`，`disableThinking=true` 时 `body.putAll(thinkingOffParams)`；4-arg 旧 ctor 委托 `false,Map.of()`（不破坏既有调用/测试）。`LlmProperties` 加 `disableThinking`(默认 false)+`Provider.disableThinkingParams`(Map)；`LlmConfig.buildRoutingExecutor` 透传；`LlmConfigReporter` 报告「关思考=...」+每 provider 参数。**坑**：两家关思考参数**不同**——单一全局 `enable_thinking:false` 对 SenseNova 无效（它认 reasoning_effort）。TDD：3 测（boolean 合并/string 合并/开关关不合）。663 GREEN。

**官方文档核实（Tavily，2026-09-10，reference）：**
- **SiliconFlow**(`api.siliconflow.cn/v1`，文档 docs.siliconflow.com/cn/api-reference/chat-completions)：`enable_thinking` boolean **默认 true**（思考默认开）→传 `false` 关闭；仅推理模型支持，发给非推理模型会 `400 code=20015 "does not support parameter enable_thinking"`。`max_tokens` **不含** CoT（CoT 由 `thinking_budget` 默认 4096 限）。响应思考字段 `reasoning_content`。型号 `Qwen/Qwen3.5-35B-A3B`/`Qwen3.5-27B` 均**合法**（在官方模型列表）。
- **SenseNova 商汤**(`token.sensenova.cn/v1`，文档 raw.githubusercontent.com/OpenSenseNova/SenseNova6.7/main/API_CN.md)：`max_tokens` **含** reasoning（与 SF 相反→推理重仍可能空 content）。响应思考字段 `message.reasoning`（非 reasoning_content，但空 content 检测只读 content 两家适用）。型号 `sensenova-6.8-flash-lite`(small 备，合法，6.8 是现售最新)/`deepseek-v4-flash`(large 备，合法，Token Plan 表"支持思考/非思考模式")。`deepseek-v4-flash` 关思考用 `reasoning_effort:"none"`(none/low/medium/high，**不认** enable_thinking)；flash-lite 官方 API_CN.md **无**任何思考开关字段→其 provider disable-thinking-params 留空 `{}`（关思考对它无效，但主修好后基本不走到它）。
- QueryRewriter「全部模型不可用」根因=主 siliconflow-small+备 flash-lite 均思考开、复杂改写 prompt 在 1024 耗尽→空 content→抛 LlmUnavailable→QueryRewriter 捕获降级 rawInput(不阻塞)。关思考后主直接产出 content，该现象消失。

**真实冒烟**：`RealLlmFailoverSmokeTest` `@EnabledIfEnvironmentVariable(SF_KEY/DS_KEY matches "sk-.+")` 门控，无 key 整类跳过（不影响 `./mvnw test` 基线）。`max_tokens=1024` 镜像生产 `ChatLlmService.DEFAULT_MAX_TOKENS`（256 令 reasoning 截断）；`modelId` 断言验「主成功」(siliconflow-large) /「断主 key→切备」(deepseek-large)——loose content 断言会在主静默切备时给假绿。密钥不在任何 shell profile/.env/项目，须 `export SF_KEY=sk-... DS_KEY=sk-...` 后 `./mvnw -Dtest=RealLlmFailoverSmokeTest test`。SF_KEY/DS_KEY 分别主 SiliconFlow、备 SenseNova(deepseek-v4-flash)。

**Part A 意图驱动关思考改造（已并入，693 测 GREEN）：** 关思考从「构造期全局开关」改为「每请求意图驱动」——`disableThinking = (intent==CHIT_CHAT) || !thinkingEnabled` 在 `ChatLlmService.invoke` 算定，经 `GatewayRequest.disableThinking`→`FailoverExecutor`→`RoutingModelExecutor`→`LlmRequest.disableThinking` 透传到 `OpenAiModelExecutor`（`request.disableThinking() && !thinkingOffParams.isEmpty()` 时合并）。闲聊恒关思考（省 token + 避推理模型 max_tokens 不足→空 content→LlmUnavailable）；非闲聊由 `llm.thinking.enabled`（默认 true）定。`LlmProperties` flat `disableThinking`→嵌套 `Thinking{enabled}`；`OpenAiModelExecutor` 删 `disableThinking` 字段改读 request；`GatewayConfig` `@Value("${llm.thinking.enabled:true}")` 注入。详见 [[phase21-hybrid-retrieval-design]]（同一 693 基线，TDD 3 测验闲聊恒关/非闲聊开关两态）。

**Part B max_tokens 旋钮（已并入，694 测 GREEN，新增 1 测 `customMaxTokens_propagatedToGatewayRequest`）：** 实跑发现推理模型（Qwen3.5）思考开 + `max_tokens=1024` → reasoning 耗尽预算 → `content:""`/`finish_reason:"length"` → 抛 `LlmUnavailable` → 切备/容灾耗尽。Part B 加配置旋钮给 reasoning+答案留预算：`llm.max-tokens`（默认 1024）经 `GatewayConfig` `@Value("${llm.max-tokens:1024}")`→`ChatLlmService` 6-arg ctor `maxTokens` 字段→`GatewayRequest`→`LlmRequest` 透传（`invoke` 用 `maxTokens` 取代硬编码 `DEFAULT_MAX_TOKENS`）。**与 disableThinking 正交**：空 content 双解——(1)关思考 `thinking.enabled=false`（**proven** 根因解：关后主直接产出 content，闲聊降级现象消失）；(2)调高 `llm.max-tokens: 4096`（**unverified**：保留思考但给预算；注意 SiliconFlow 的 max_tokens **不含** CoT，由 thinking_budget 默认 4096 限——故 SF 上调 max_tokens 未必解空 content，SenseNova 的 max_tokens **含** reasoning 则有效）。**结论**：实跑修空 content 首选关思考（proven）；max_tokens 旋钮是保留思考的辅助杠杆，对 SenseNova 备链更有意义。LC4j 接入核验见 [[langchain4j-boot4-compat-findings]]（可用但 Jackson 2/3 双版本不净，仍延后）。
