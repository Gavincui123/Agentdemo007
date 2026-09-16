---
name: phase15-t70-eval-controller-design
description: Phase 15 T70 评测端点：EvalController POST /eval/run + EvalAuthenticator seam + EvalSuite wrapper(record非裸List<String>避Spring注入歧义)+EvalConfig @Bean+鉴权401与兄弟控制器同形HTTP200
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T11:52:15.705Z
---

Phase 15·T70 评测端点（`com.agentdemo007.eval` 包）。

**端点：** `POST /eval/run`（{@link com.agentdemo007.eval.EvalController}）：取 `X-Eval-Token` 头 → `EvalAuthenticator.authenticate` → 失败 `UnifiedResponse.error(UNAUTHORIZED)`（code=401, data=null, 不返回报告）；成功则按 `EvalSuite.resources` 加载各 `eval/*.json` → `EvalExecutor.run` → `UnifiedResponse.success(EvalReport)`。

**鉴权收口（验收：生产无鉴权不可访问 /eval/run）：** 与兄弟控制器（RouteWeightController/FeedbackController）同形——HTTP 200 + code 体内表达（②降级不 5xx）。"不可访问"= 不外泄评测报告（data=null），非 HTTP 401 状态。`ErrorCode.UNAUTHORIZED(401,"未授权")` 复用既有枚举（T1 即有，无需新增）。

**EvalAuthenticator（seam）：** @FunctionalInterface `boolean authenticate(String token)`。dev 默认 @Bean（@ConditionalOnMissingBean）读 `agentdemo.eval.token`（默认 `dev-eval-token`）逐字比对——机制强制鉴权（无 token 即拒），prod 用真实密钥/SSO bean 覆盖。与 ConfigWriter/RegistrationWriter 同为引擎无关 seam 模式。

**EvalSuite（wrapper record `List<String> resources`）：** 非裸 `List<String>`——避 Spring 对 `List<String>` 构造注入按"所有 String bean"解析的歧义（Spring `List<X>` 注入收集所有 X 类型 bean，String 非 bean 类型 → 空/报错），且强类型收口（第四原则）。承载 dev-plan §Phase15 标准 8 stage 资源（injection/intent/routing/rag/tool/hitl/degradation/audit）。

**EvalConfig（@Configuration）：** @Bean `EvalExecutor`（1-arg 走 defaultMapper，与单测同构，已 disable FAIL_ON_UNKNOWN_PROPERTIES）+ @Bean @ConditionalOnMissingBean `EvalAuthenticator` + @Bean `EvalSuite`（标准 8）。`EvalController` 为 @RestController 由组件扫描拾取。

**测试（538 GREEN）：** `EvalControllerTest` 3 例——无 token→401、错 token→401、有效 token→200+EvalReport（stage=injection, totalCases>0）。注入受控 strict authenticator（仅 "secret" 通过）+ trivial PipelineExecutor（恒 ok，不验通过率，通过率属 EvalExecutorTest）+ 单文件 EvalSuite（仅 injection.json，T69 已验可解析，隔离其余 stage 解析耦合）。

相关：[[phase15-t69-eval-executor-design]]、[[phase15-t66-route-weight-design]]、[[degradation-and-eval-principles]]。
