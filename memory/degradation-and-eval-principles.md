---
name: degradation-and-eval-principles
description: "User-mandated design rules — 话术 short-circuit, per-step degradation, per-stage eval data, unified convergence (收口)"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-03T11:24:23.853Z
---

用户明确要求的三条设计原则，后续每个 Phase 都必须遵守：

1. **话术短路**：4xx / 注入攻击 / 攻击类 / 依赖故障 等场景，**不得返回技术错误码**，直接用 `DegradationPhraseCenter` 预设话术作为"正常"对话回复（HTTP 200 + `UnifiedResponse{data:{reply,degraded:true,scenario}}`）+ 审计 + **跳过后续所有步骤**（零 LLM）。面向 C 端始终"正常"。
2. **每步降级预设**：流水线每一步都要有降级备用，保证任何单点故障下程序仍能跑通、有话术兜底（见 DEVELOPMENT-PLAN.md §5.12 降级预设表）。
3. **环节测评数据预设**：每个关键环节实现时**同步**预置 golden 测评数据到 `src/main/resources/eval/{stage}.json`，供 Phase 15 `/eval/run` 统一消费，不留到最后补。
4. **统一收口（最重要）**：每一步都经统一收口，防止数据结构等不一致。收口三件套（`common/pipeline`）：`PipelineContext`（状态唯一收口，流过所有步骤，禁止各步私造数据结构互相传参）、`StepOutcome`（sealed 每步统一产出：`Proceed`/`ShortCircuit`/`Degrade`，把话术短路+降级+收口机械统一为同一出口形状）、`PipelineOrchestrator`（按 `@Order` 收集步骤，统一处置三态，异常收口 INTERNAL 话术不抛 5xx）。对外收口：`PipelineResult`→`UnifiedResponse`，`TraceId` 收口 traceId。用户称这是"最重要的"一条。

**Why:** 用户反馈"返回原始错误码给用户"是错的（这里不对）；面向用户要始终正常话术、程序要能跑通、评测数据要随环节沉淀；层间各自定义产出类型会漂移，必须统一收口防不一致（如数据结构）。

**How to apply:** 新增任何接入层/流水线步骤时：① 先想它失败/被拦截时回哪句 话术（在 DegradationScenario 枚举或 §5.12 表里）；② 同步写 `eval/{stage}.json`；③ 不要让该步的异常裸抛成 5xx 给用户；④ 新步骤必须实现 `PipelineStep`、经 `PipelineContext` 读写状态、返回 `StepOutcome`；新状态字段一律强类型加到 `PipelineContext`，不得在步骤间私传 bespoke 结构。参考已落地：`DegradationPhraseCenter`、`InputSecurityFilter`/`ValidationFilter` 话术化、`eval/injection.json`、`eval/degradation.json`、收口三件套（`PipelineContext`/`StepOutcome`/`PipelineStep`/`PipelineResult`/`PipelineOrchestrator`）、`TraceId`。相关：[[spring-boot-4-jackson3-gotchas]]
