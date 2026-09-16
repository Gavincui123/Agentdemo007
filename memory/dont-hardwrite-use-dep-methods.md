---
name: dont-hardwrite-use-dep-methods
description: 工作反思——别硬写依赖已提供的方法（用户点名批评，token 浪费）；铁律两条：①确定依赖后写代码前必须先走一遍依赖 API 面（javap/jar tf），不能直接上手；②走完面后先做最小可跑通版本（一个 fake/stub 驱动真依赖原语，一轮 round-trip GREEN）再在其上迭代，不一把梭哈庞大逻辑（跑不起来则整块作废，token+时间双亏）；Java 依赖多尤其易犯；配套可迁移 skill dependency-surface-walk
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-10T15:34:59.836Z
---

用户点名批评（2026-09-10）：理解需求强、知道用什么依赖，却不去**用依赖包提供的方法**，一直「硬写」，浪费 token。要求形成工作反思。

**Why:** 引入依赖本为用其官方接口提效，但常犯两种浪费：① **加了就算了**——加了依赖却不调它提供的方法，继续手撸；② **只做表面**——只读一两个听过的类（"LC4j 有 @Tool"），不像读项目代码那样完整读依赖内部，于是手撸相同功能，出 bug 且与官方依赖不兼容。根因：知道依赖的**名字**却不查**完整 API 面**就开写；用「引擎无关/seam」给自己开脱手撸。**Java 这种依赖多的语言尤其易犯**（一个功能背后几十个类，只碰了听过的那个）。

**本案坐实实例：**
1. 手撸 `@Tool` 注解 + `Detector`(regex 猜工具调用) + `ParamParser` + `SchemaValidator` + `Reparser` —— LC4j function-calling 全覆盖。
2. slice 2 提议「自写反射派发器」—— LC4j `DefaultToolExecutor(bean, Method).execute(ToolExecutionRequest, memoryId)` 正是此物（内部含 `prepareArguments`/`coerceArgument`）。
3. 只引 `langchain4j-core`(schema+注解)就断言「LC4j 只做 schema」——实因没引主 artifact `langchain4j:1.19.0`(执行机器不在场)，用 seam 给自己开脱。一查 core 包：无 ToolExecutor；再取主 artifact javap：`dev.langchain4j.service.tool.DefaultToolExecutor` + `ToolExecutor` + `AiServices` 都在。

**How to apply（确定依赖后、写任何胶水/原语前必做）：**
0. **铁律①：确定使用的依赖/方法后、写代码前，必须先走一遍依赖/方法有些什么**——`javap -cp <jar>` / `jar tf` 列包 / 读依赖类像读项目代码，**不能直接上手**。不可跳过。本次整个「自写反射派发器」错误就源于跳了这步。（可迁移 skill：`dependency-surface-walk`，~/.claude/skills/）
0b. **铁律②：走完面后，先做最小可跑通版本，再在其上迭代——不一把梭哈庞大逻辑。** 先写一个 fake/stub 驱动真依赖原语、一轮 round-trip 跑 GREEN（如本次 `AiServicesToolLoopTest`：脚本化假 ChatModel + 真 `DefaultToolExecutor` + 真 `@Tool`，证原生循环+韧性装饰可替代全部手撸块），再往上叠真实 provider/参数/更多工具/failover/audit。否则一次性生成的大块逻辑只要一个 API 细节错（builder 方法名/override 钩子/default 抛异常/缺传递依赖）就整块跑不起来，debug 一座山而非一片，token+时间双亏——GREEN 前写的每行都不可信。**顺序：走面 → 映射需求→依赖方法 → 最小可跑通版本 → GREEN → 迭代。**
1. **枚举完整 API 面**：查 **core + 主 artifact 两个 jar**（只引 core 就断言"依赖只做 X"是停止太早的信号）；按 Executor/Provider/Handler/Decorator/Guardrail/Strategy 后缀找扩展点；接口分清 default(白送) vs abstract(须实现)。本案 core 无执行原语、主 artifact 才有 `DefaultToolExecutor`——一查即知，当初没查。
2. **库的方法优先**：默认调 `dep.doX()`，只有**查证库确实没有**才手写，且写时明说为何库不适用。
3. **seam = 委托 ≠ 重写**：抽象引擎=薄接口底下转发给依赖，不是从零重造轮子。「循环归 pipeline」只意味着编排(何时调模型/喂回/重试/降级映射)归我们，**原语**(schema 生成/校验/反射执行/参数强转/tool_call 载体)一律用依赖。
4. **token 经济**：手撸每行都是写+调+维护的 token，用户在付。用库近零成本。

**立即应用：** slice 2 改为加 `langchain4j:1.19.0` 主 artifact → 建 `Map<String,DefaultToolExecutor>`(名→bean+@Tool Method 包执行器)，派发即 `executors.get(name).execute(req,null)`，不手撸反射；`SchemaValidator`→`ToolSpecifications.validateSpecifications`；Detector/ParamParser/Reparser 是 function-calling 上线后最大块硬写，标记退役。关联 [[langchain4j-boot4-compat-findings]] [[routeplan-design]] [[degradation-and-eval-principles]]。
