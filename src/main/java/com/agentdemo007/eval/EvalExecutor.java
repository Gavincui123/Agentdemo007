package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 评测执行器（Phase 15·T69 环节测评，③环节测评原则）。
 *
 * <p>加载 eval/*.json golden 数据集 → 经 {@link PipelineExecutor} 跑每例 → 派生 {@link ActualOutcome}
 * → 对照 {@link EvalExpected} 非空字段判定 → 按 stage 聚合 {@link EvalReport}。
 *
 * <p>派生规则（scenario 分类，对齐 {@link com.agentdemo007.common.degradation.DegradationScenario}）：
 * <ul>
 *   <li>{@code outcome}：未降级→PROCEED；降级且 scenario ∈ {UNKNOWN_INTENT, RAG_SKIP, OUTPUT_FALLBACK}→DEGRADE；否则 SHORT_CIRCUIT。</li>
 *   <li>{@code blocked}：scenario=INJECTION。</li>
 *   <li>{@code zeroLlm}：scenario ∈ {INJECTION, RATE_LIMITED, PAYLOAD_TOO_LARGE, SESSION_DOWN, MODEL_DOWN}（短路在模型调用前）。</li>
 *   <li>{@code shortCircuit}：outcome=SHORT_CIRCUIT。</li>
 * </ul>
 *
 * <p>{@code escalatedToModel} 不在上下文可派生范围，期望有该字段则跳过比较（已知限制，需 LLM 调用追踪补齐）。
 *
 * <p>②每步降级：单例执行异常不影响整体——异常用例记为失败（mismatch 含异常信息），继续后续用例。
 * plain class + @Bean 工厂（{@code EvalConfig}），依赖 {@link PipelineExecutor}，可注入替身单测。
 */
public class EvalExecutor {

    /** DEGRADE 场景（降级但继续推进，非短路）。 */
    private static final Set<String> DEGRADE_SCENARIOS =
            Set.of("UNKNOWN_INTENT", "RAG_SKIP", "OUTPUT_FALLBACK");
    /** 零 LLM 场景（短路在模型调用前，§5.11）。 */
    private static final Set<String> ZERO_LLM_SCENARIOS =
            Set.of("INJECTION", "RATE_LIMITED", "PAYLOAD_TOO_LARGE", "SESSION_DOWN", "MODEL_DOWN");

    private final PipelineExecutor pipelineExecutor;
    private final ObjectMapper objectMapper;

    public EvalExecutor(PipelineExecutor pipelineExecutor) {
        this(pipelineExecutor, defaultMapper());
    }

    /** Spring 注入构造：用容器配置的 ObjectMapper（Boot 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}）。 */
    public EvalExecutor(PipelineExecutor pipelineExecutor, ObjectMapper objectMapper) {
        this.pipelineExecutor = pipelineExecutor;
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper defaultMapper() {
        // 忽略 eval JSON 中的 context 前置条件字段（执行器不搭建环境条件）
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    /**
     * 从 classpath 加载单个 eval 文件并解析为 {@link EvalFile}（本地兜底路径，source=local）。
     *
     * @param classpathResource classpath 路径（如 {@code eval/injection.json}）
     * @return 解析后的 eval 文件；资源不存在或解析失败抛 {@link IllegalStateException}
     */
    public EvalFile loadFile(String classpathResource) {
        try (InputStream in = EvalExecutor.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("评测资源不存在：" + classpathResource);
            }
            return objectMapper.readValue(in, EvalFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("评测资源加载失败：" + classpathResource, e);
        }
    }

    /**
     * 从内容字符串解析单个 eval 文件（2026-09-17 Nacos 动态化）：内容来自
     * {@link EvalContentResolver#resolve}（Nacos 实时拉取或本地 classpath 兜底），
     * source 随 {@link EvalFile#source()} 透传至报告。解析失败抛 {@link IllegalStateException}，
     * 由 {@code EvalController} 逐 stage 降级（跳过该 stage，其余照常）。
     */
    public EvalFile parseFile(String classpathResource, String content, String source) {
        try {
            EvalFile parsed = objectMapper.readValue(content, EvalFile.class);
            return new EvalFile(parsed.stage(), parsed.description(), parsed.cases(), source);
        } catch (Exception e) {
            throw new IllegalStateException("评测数据解析失败：" + classpathResource, e);
        }
    }

    /**
     * 跑一组 eval 文件（每文件一个 stage），返回聚合报告。
     */
    public EvalReport run(List<EvalFile> files) {
        List<StageReport> stages = new ArrayList<>();
        int totalPassed = 0;
        int totalCases = 0;
        for (EvalFile file : files) {
            StageReport stage = runStage(file);
            stages.add(stage);
            totalPassed += stage.passed();
            totalCases += stage.total();
        }
        return new EvalReport(stages, totalPassed, totalCases);
    }

    /** 跑单个 stage（公开：{@code EvalJobManager} 异步逐 stage 驱动，进度快照按 stage 产出）。 */
    public StageReport runStage(EvalFile file) {
        List<CaseResult> caseResults = new ArrayList<>();
        int passed = 0;
        for (EvalCase evalCase : file.cases()) {
            CaseResult result = runCase(evalCase);
            if (result.passed()) {
                passed++;
            }
            caseResults.add(result);
        }
        int total = file.cases().size();
        double passRate = total == 0 ? 0.0 : (double) passed / total;
        return new StageReport(file.stage(), passed, total, passRate, caseResults, file.source());
    }

    private CaseResult runCase(EvalCase evalCase) {
        PipelineContext context = new PipelineContext("eval-" + evalCase.id(), evalCase.input());
        ActualOutcome actual;
        try {
            PipelineResult pipelineResult = pipelineExecutor.run(context);
            actual = derive(pipelineResult, context);
        } catch (Exception e) {
            // ②降级：执行异常→用例记失败，不阻塞整体
            return new CaseResult(evalCase.id(), false,
                    List.of("execution-error: " + e.getMessage()));
        }
        List<String> mismatches = compare(evalCase.expected(), actual);
        return new CaseResult(evalCase.id(), mismatches.isEmpty(), mismatches);
    }

    private ActualOutcome derive(PipelineResult result, PipelineContext context) {
        String scenario = result.scenario() != null ? result.scenario() : "NONE";
        boolean degraded = result.degraded();
        String intent = context.intent() != null ? context.intent().name() : null;
        String route = context.routeType() != null ? context.routeType().name() : null;
        String selectedModel = context.selectedModelId();
        String outcome = deriveOutcome(degraded, scenario);
        boolean blocked = "INJECTION".equals(scenario);
        boolean shortCircuit = "SHORT_CIRCUIT".equals(outcome);
        boolean zeroLlm = ZERO_LLM_SCENARIOS.contains(scenario);
        // Phase 20（T92）可派生字段：约束改写补全槽 + RAG 片段（含时效标注）
        List<String> enrKeywords = (context.queryEnrichment() != null)
                ? context.queryEnrichment().keywords() : List.of();
        List<String> rags = context.ragFragments();
        boolean hasFrags = rags != null && !rags.isEmpty();
        return new ActualOutcome(scenario, degraded, intent, route, selectedModel,
                outcome, blocked, zeroLlm, shortCircuit,
                enrKeywords, rags != null ? rags : List.of(), hasFrags);
    }

    private String deriveOutcome(boolean degraded, String scenario) {
        if (!degraded) {
            return "PROCEED";
        }
        return DEGRADE_SCENARIOS.contains(scenario) ? "DEGRADE" : "SHORT_CIRCUIT";
    }

    /** 对照期望非空字段与实际产出，收集不匹配项。 */
    private List<String> compare(EvalExpected expected, ActualOutcome actual) {
        List<String> mismatches = new ArrayList<>();
        check(mismatches, "scenario", expected.scenario(), actual.scenario());
        check(mismatches, "intent", expected.intent(), actual.intent());
        check(mismatches, "route", expected.route(), actual.route());
        check(mismatches, "selectedModel", expected.selectedModel(), actual.selectedModel());
        check(mismatches, "outcome", expected.outcome(), actual.outcome());
        checkBoolean(mismatches, "degraded", expected.degraded(), actual.degraded());
        checkBoolean(mismatches, "blocked", expected.blocked(), actual.blocked());
        checkBoolean(mismatches, "zeroLlm", expected.zeroLlm(), actual.zeroLlm());
        checkBoolean(mismatches, "shortCircuit", expected.shortCircuit(), actual.shortCircuit());
        // Phase 20（T92）：约束改写补全槽 + RAG 片段（含时效标注）
        checkContains(mismatches, "queryEnrichmentContains",
                expected.queryEnrichmentContains(), actual.queryEnrichmentKeywords());
        checkAnyContains(mismatches, "ragFragmentsContain",
                expected.ragFragmentsContain(), actual.ragFragments());
        checkBoolean(mismatches, "hasFragments", expected.hasFragments(), actual.hasFragments());
        // escalatedToModel：不可派生，跳过（已知限制）
        return mismatches;
    }

    /** 期望某精确词在补全槽关键词列表中（T88 约束改写补全）。 */
    private void checkContains(List<String> mismatches, String field, String expected, List<String> actual) {
        if (expected != null && !actual.contains(expected)) {
            mismatches.add(field + ": expected~contains=" + expected + " actual=" + actual);
        }
    }

    /** 期望某文本子串在任一 ragFragments 元素中（T89 Hybrid 命中 / T90 时效标注）。 */
    private void checkAnyContains(List<String> mismatches, String field, String expected, List<String> actual) {
        if (expected != null && actual.stream().noneMatch(f -> f != null && f.contains(expected))) {
            mismatches.add(field + ": expected~anyContains=" + expected + " actual=" + actual);
        }
    }

    private void check(List<String> mismatches, String field, String expected, String actual) {
        if (expected != null && !expected.equals(actual)) {
            mismatches.add(field + ": expected=" + expected + " actual=" + actual);
        }
    }

    private void checkBoolean(List<String> mismatches, String field, Boolean expected, boolean actual) {
        if (expected != null && expected != actual) {
            mismatches.add(field + ": expected=" + expected + " actual=" + actual);
        }
    }
}
