---
name: segmented-systemprompt-intent-design
description: "分段式 system prompt 装配器+三层意图识别优化**设计已定稿**(spec at docs/superpowers/specs/2026-09-14-..., 待转 writing-plans/实现)：片段{prompt,scene,sort,enabled}存 Nacos AiService 模板 key=system-prompt-segments(bare YAML数组)经 NacosPromptSource gRPC 热更(@RefreshScope 不可用已订正)；SystemPromptAssembler 按 scene(all/粗Intent/细RoutePlan.intent)选+sort降序拼接替换 system-anchor(迁完不留灰度);层3改旗舰(Qwen3.5-35B-A3B默认)+关思考+5轮上限+动态注入防御(当前轮→3层拦截/历史轮→剔除/全注入→规则兜底);A先B后"
metadata:
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-14T06:52:08.138Z
---

分段式 system prompt 装配器 + 三层意图识别优化——**设计 spec 已定稿**（2026-09-14）。**子项目 A（分段装配器）实现计划已出**：`docs/superpowers/plans/2026-09-14-segmented-systemprompt-assembler.md`（5 Task TDD：PromptSegment/SystemPromptAssembler/SystemAnchorLayer 接线/ContextBuilderTest 修/Nacos 配置），待执行；**子项目 B（层3 旗舰意图）计划待 A 完成后出**。spec 全文：`docs/superpowers/specs/2026-09-14-segmented-systemprompt-intent-design.md`。

**两子项目**（耦合点=意图产出→片段 scene 选择；实现 **A 先 B 后**，各自 TDD 不互锁）：
- **A 分段式 system prompt 装配器**：片段 `PromptSegment(id,prompt,scene,sort,enabled)` 存 Nacos **AiService 提示词模板** key=`system-prompt-segments`（内容=bare YAML 数组，不带 app.prompt.segments 外层），经 `PromptRegistry.get(key,latest)`→`PromptTemplate.template()`→SnakeYAML `new Yaml().load()` 解析。**热更走 `NacosPromptSource` AiService gRPC push**（与 system-anchor/clarify 同已验证通路）——原 `@ConfigurationProperties`+`@RefreshScope` 方案**订正废弃**（@RefreshScope 来自 spring-cloud-context，本工程 spring-alibaba-nacos-config 独立组件未引，不在 classpath；用户2026-09-14确认改 AiService 模板通路）。`SystemPromptAssembler.assemble(coarse,fine,vars)` 选 `enabled` 片段[scene∈{all,粗Intent.name(),细RoutePlan.intent()}]→sort 降序(并列配置序稳定)→`{{var}}` render→`\n\n` 拼接→`SystemAnchorLayer.resolveSystemPrompt(ctx)` 改调它(替 system-anchor 单模板)。②降级：registry miss/解析失败/零匹配→`DEFAULT_SYSTEM_PROMPT`。`system-anchor` 内容迁入 scene=all,sort=100 片段后**key 弃用不留灰度回退**（单一真相源）。**不改 application.yml**（dev LocalPromptSource 缺 key→②降级 DEFAULT；prod NacosPromptSource 取模板）。
- **B 三层意图识别优化**：层1/2 不动(`KeywordTriageStep@150`+`IntentRecognizerImpl`规则层,针对当前轮)。**层3改旗舰语义层**：`recognize(query,history)` 新逻辑=①当前轮再扫注入(纵深兜层1漏检,返回 INJECTION→`IntentRecognitionStep@500` line64 ShortCircuit 已验)②history 截最近 5 轮(1轮=1user+1assistant,`llm.intent-recognition.history-rounds`默认5,截`2*rounds`条)③逐轮扫注入命中剔除保留干净轮④5轮全剔除→规则兜底(不调旗舰,回退规则层结果/无定论→OTHER)⑤`buildClassifyPrompt`(干净轮逐轮 PromptSanitizer 包裹)→旗舰(关思考)→parseIntent→兜底 OTHER。旗舰路由=`ChatLlmService.recognizeSemantic`(新,`llm.intent-recognition.model-id`默认 large Qwen3.5-35B-A3B+可选 fallback-model-ids,经 gateway.invoke 复用主备容灾/熔断,disableThinking=true 铁律;不复用 decide()供路由/改写小模型)。classify prompt registry 化(AiService key=intent-classify,`{{history}}`/`{{query}}` 变量,补 Q1 TODO,硬编码兜底)。

**7 决策**(用户确认)：①层3旗舰+关思考(铁律不破)②scene 粗细两级③动态注入防御(当前轮→3层拦截/历史轮→剔除不一票否决/全注入→规则兜底)④片段存 dataId JSON 数组⑤PromptSegment 含 enabled⑥system-anchor 迁完不留灰度回退⑦旗舰默认 Qwen3.5-35B-A3B。

**已知风险**：旗舰+关思考语义增益须 real-SF 冒烟坐实(单测只验路由/降级/注入)；AiService 模板热更须 real-Nacos 冒烟(NacosPromptSource gRPC push，单测只验装配/降级)；`{{var}}` 无条件渲染(条件逻辑放代码同 confirmation)。

**Why**：用户要 system prompt 动态组装(全局段+意图段+0-100优先级)+意图识别第三层旗舰带5轮上下文+防直接/间接注入。
**How to apply**：实现时先 A(装配器挂当前意图跑通)后 B(升级意图质量);每步 RED→GREEN;注入扫描复用既有 InjectionPatternRule 不重写;旗舰路由经 gateway.invoke 不另立栈。关联 [[q1-nacos-prompt-management]](registry/intent-classify) [[phase8-context-design]](SystemAnchorLayer/runtime 块) [[phase6-7-understanding-intent-design]](三层意图) [[degradation-and-eval-principles]](②降级) [[phase-llm-primary-backup-breaker]](关思考铁律/主备容灾) [[business-tools-workflow-dag]](confirmation {{var}}无条件同因)。
