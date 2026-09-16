---
name: q1-nacos-prompt-management
description: "Q1 prompt 管理已实现(891测)——clarify 话术 registry 化 + PromptSourceConfig @Bean(app.prompt.source=nacos→NacosPromptSource 失败降 LocalPromptSource)；切 Nacos 需 PROMPT_SOURCE=nacos + spring.nacos.config.*；3 点(RoutePromptBuilder/IntentRecognizer/QueryRewriter)仍硬编码 TODO"
metadata:
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-13T16:08:53.824Z
---

Q1「我要退货返政策 dump」实测修复——prompt 管理通路**已实现**（2026-09-14，全量 891 GREEN / 9 skipped）。根因详见 [[business-tools-workflow-dag]] 实测发现段（主因=`app.workflow.enabled=false` 工作流门控关着→entity-gate 澄清话术路径全死，非 prompt 本身）。本记忆只记 prompt 管理通路设计。

**已落码**：
- **clarify 话术 registry 化**（Slice 1）：`WorkflowExecutionStep` 加 4-arg @Autowired ctor 注 `PromptRegistry`（2/3-arg ctor 委派 null 保旧测）；`clarifyMessage(intent)` 改 `registry.get(clarifyPromptKey(intent), VersionSpec.latest()).map(t->t.render(Map.of())).filter(非空).orElse(硬编码)`。key 映射：`return_request→clarify-return`、`refund_request→clarify-refund`、其他→null（走硬编码兜底）。TDD 2 测（registry 有模板→用 registry / registry miss→回退硬编码）GREEN。`system-anchor`（SystemAnchorLayer system prompt）早已 registry 化。
- **Gap A 装配**（`PromptSourceConfig` 新文件）：`@Bean AiServiceFactory`（函数式 seam = `AiFactory::createAiService`）+ `@Bean @ConditionalOnProperty(name="app.prompt.source", havingValue="nacos") PromptRegistry` = `buildNacosPromptSource(factory, buildNacosProps(env))`：读 `spring.nacos.config.server-addr/namespace/username/password` → `PropertyKeyConst.*` → `AiFactory.createAiService(props)` → `new NacosPromptSource(aiService)`；try 失败→`log.warn`+回退 `new LocalPromptSource`（降级不崩）。4 测 GREEN。
- **GatewayConfig 默认源切换**：默认 `PromptRegistry` 从 `@ConditionalOnMissingBean` 改 `@ConditionalOnProperty(name="app.prompt.source", havingValue="local", matchIfMissing=true)`（与 nacos 互斥，避 bean-override 竞态）。
- **application.yml**：`app.prompt.source: ${PROMPT_SOURCE:local}`（缺省 local）。

**切 Nacos 激活路径**：设 `PROMPT_SOURCE=nacos` + 配 `spring.nacos.config.server-addr/namespace/username/password` → `NacosPromptSource` @Bean 装配 → `clarify-return`/`clarify-refund`/`system-anchor` 走 Nacos `AiService.getPrompt/getPromptByVersion/getPromptByLabel`（md5 + gRPC 热推，`NacosException`→empty 降级）。Nacos 侧 = 3.x AI 提示词管理（控制台 AI 资源→提示词模板，promptKey `system_anchor`/`clarify_return`/`clarify_refund`/`route_prompt`/`intent_classify`/`rewriter`）。

**仍 TODO（Gap B 消费接线）**：3 处真 prompt 点仍硬编码未走 `registry.get`——`RoutePromptBuilder.build`、`IntentRecognizerImpl.buildClassifyPrompt`、`QueryRewriter`。接法：各点改 `registry.get(key, VersionSpec.latest()).map(t->t.render(vars)).orElse(硬编码)`，保留降级。最小路径先 `LocalPromptSource.put` 现有串验行为不变再切 Nacos。

**Why**：用户要求"依靠 Nacos 做 prompt 管理 + PromptBuilder 动态组装"，且实测话术错乱根因之一是 prompt 未走 registry（虽非主因）。
**How to apply**：改任一 prompt 点时先查是否已 registry 化（`clarifyMessage`/`SystemAnchorLayer` 是）/ 否则按 Gap B 模式接 registry 保留硬编码降级；新增 prompt 点同步加 Nacos promptKey + LocalPromptSource 默认串。关联 [[nacos-prompt-registry-fit]] [[nacos-config-mechanism]] [[business-tools-workflow-dag]]。
