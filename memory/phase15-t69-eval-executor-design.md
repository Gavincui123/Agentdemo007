---
name: phase15-t69-eval-executor-design
description: Phase 15 T69 评测执行器 EvalExecutor：加载 eval/*.json→PipelineExecutor 跑→ActualOutcome 派生→EvalExpected 非空对照→StageReport/EvalReport 收口；escalatedToModel 不可派生跳过；②降级单例异常记失败不阻塞
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T11:48:14.876Z
---

Phase 15·T69 ③环节测评执行器（`com.agentdemo007.eval` 包）。

**核心流：** `EvalExecutor.run(List<EvalFile>)` → 每 `EvalFile` 一 stage → `runStage` → `runCase`：`new PipelineContext("eval-"+id, input)` → `pipelineExecutor.run(ctx)` → `derive` 派生 `ActualOutcome` → `compare(expected, actual)` 收集 mismatches → `CaseResult(id, mismatches.isEmpty(), mismatches)`。聚合 `StageReport(stage, passed, total, passRate, cases)` + `EvalReport(stages, totalPassed, totalCases)`。

**派生规则（scenario 分类，对齐 DegradationScenario）：**
- `outcome`：未降级→PROCEED；降级且 scenario∈{UNKNOWN_INTENT,RAG_SKIP,OUTPUT_FALLBACK}→DEGRADE；否则 SHORT_CIRCUIT。
- `blocked`：scenario=INJECTION。`zeroLlm`：scenario∈{INJECTION,RATE_LIMITED,PAYLOAD_TOO_LARGE,SESSION_DOWN,MODEL_DOWN}。`shortCircuit`：outcome=SHORT_CIRCUIT。
- `escalatedToModel` 不可从上下文派生 → 期望有则跳过比较（已知限制，需 LLM 调用追踪补齐）。

**对照（④统一收口·强类型非 Map）：** `EvalExpected` record 全可空字段（scenario/intent/route/selectedModel/outcome/degraded/blocked/zeroLlm/shortCircuit/escalatedToModel），`compare`/`check`/`checkBoolean` 只比非空期望。

**②每步降级：** 单例 `pipelineExecutor.run` 抛异常 → 该例记 `CaseResult(id, false, ["execution-error: ..."])`，不阻塞后续。

**JSON 加载：** `loadFile(classpathResource)` 用 `JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()`（Jackson 3 `tools.jackson`）忽略 eval JSON 中的 `context` 前置条件字段（执行器不搭建环境条件，仅跑 input+对照 expected）。资源不存在/解析失败→IllegalStateException。

**构造：** plain class + 1-arg `EvalExecutor(PipelineExecutor)` 委托 2-arg `this(pe, defaultMapper())`；@Bean 工厂延后到 T70 EvalConfig 补。

**测试：** `EvalExecutorTest` 用受控 fake PipelineExecutor（按 input 关键词设 context 字段+返回受控 PipelineResult）+ 3 case（2 pass 1 mismatch）断言计数/per-case/mismatch；`loadFile` 加载真 injection.json 断言 8 case + inj-001。全套 535 测试 GREEN。相关：[[phase15-t66-route-weight-design]]、[[phase15-t67-feedback-data-layer-design]]、[[phase15-t68-finetune-loop-design]]。
