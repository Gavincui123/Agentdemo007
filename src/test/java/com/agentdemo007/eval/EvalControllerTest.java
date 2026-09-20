package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测端点控制器测试（Phase 15·T70 + 2026-09-18 异步化）。
 *
 * <p>{@code POST /eval/run}：鉴权（401）→ stage 过滤（BAD_REQUEST）→ 启动异步作业<b>秒回</b>
 * 进度快照（不再同步跑完——全量真 LLM 曾拖爆前端超时且无进度）；作业在跑 → CONFLICT。
 * {@code GET /eval/progress}：鉴权 → 快照。统一旋钮 IP 封禁（外部禁 eval）在既有用例中
 * 以关闸兼容构造关闭，不影响本文件断言；封禁语义由专测覆盖。
 *
 * <p>作业执行器注入同步 {@code Runnable::run}（start 后即终态）或挂起 Executor（钉 CONFLICT）；
 * trivial 流水线（恒 ok）只验接线与收口，不验通过率（属 {@link EvalExecutorTest} 职责）。
 */
class EvalControllerTest {

    /** 本机直连请求桩（无代理头 + 回环 remoteAddr → isLoopbackClient=true）。 */
    private static HttpServletRequest localRequest() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    private EvalExecutor executorWithTrivialPipeline() {
        PipelineExecutor trivial = new PipelineExecutor() {
            @Override
            public PipelineResult run(PipelineContext context) {
                return PipelineResult.ok("ok");
            }
        };
        return new EvalExecutor(trivial);
    }

    private EvalAuthenticator strictAuthenticator() {
        // strict：仅 "secret" 通过；null/任意非 "secret" → false
        return token -> "secret".equals(token);
    }

    private EvalSuite singleFileSuite() {
        return new EvalSuite(List.of("eval/injection.json"));
    }

    /** local-only 解析器（禁用 Nacos）：控制器单测不打真 Nacos，走本地 classpath 兜底路径。 */
    private EvalContentResolver localOnlyResolver() {
        return new EvalContentResolver(null, false, "DEFAULT_GROUP", "agentdemo-eval", 3000L);
    }

    /** 同步作业执行器：start() 内直接跑完 → 返回时 progress 已是终态。 */
    private EvalJobManager syncJobManager() {
        return new EvalJobManager(executorWithTrivialPipeline(), localOnlyResolver(),
                Runnable::run);
    }

    /** 挂起作业执行器：任务永不执行 → running 恒 true（钉并发拒绝）。 */
    private EvalJobManager suspendedJobManager() {
        return new EvalJobManager(executorWithTrivialPipeline(), localOnlyResolver(),
                task -> { /* 挂起：不执行，running 恒 true */ });
    }

    @Test
    void run_withoutToken_returnsUnauthorized() {
        EvalController controller = new EvalController(
                strictAuthenticator(), singleFileSuite(), syncJobManager());

        UnifiedResponse response = controller.run(null, null, localRequest());

        assertThat(response.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(response.data()).isNull();
    }

    @Test
    void run_withInvalidToken_returnsUnauthorized() {
        EvalController controller = new EvalController(
                strictAuthenticator(), singleFileSuite(), syncJobManager());

        UnifiedResponse response = controller.run("wrong-token", null, localRequest());

        assertThat(response.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(response.data()).isNull();
    }

    @Test
    void run_withValidToken_startsJobAndReturnsProgress() {
        EvalController controller = new EvalController(
                strictAuthenticator(), singleFileSuite(), syncJobManager());

        UnifiedResponse response = controller.run("secret", null, localRequest());

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data()).isInstanceOf(EvalProgress.class);
        EvalProgress progress = (EvalProgress) response.data();
        assertThat(progress.running()).isFalse(); // 同步执行器：返回时已完成
        assertThat(progress.report()).isNotNull();
        assertThat(progress.report().stages()).hasSize(1);
        assertThat(progress.report().stages().get(0).stage()).isEqualTo("injection");
        assertThat(progress.report().totalCases()).isGreaterThan(0);
    }

    @Test
    void progress_returnsSnapshot_afterRun() {
        EvalJobManager manager = syncJobManager();
        EvalController controller = new EvalController(strictAuthenticator(), singleFileSuite(), manager);

        controller.run("secret", null, localRequest());
        UnifiedResponse response = controller.progress("secret", localRequest());

        assertThat(response.code()).isEqualTo(0);
        EvalProgress progress = (EvalProgress) response.data();
        assertThat(progress.report()).isNotNull();
        assertThat(progress.report().totalCases()).isGreaterThan(0);
    }

    @Test
    void progress_withoutToken_returnsUnauthorized() {
        EvalController controller = new EvalController(
                strictAuthenticator(), singleFileSuite(), syncJobManager());

        UnifiedResponse response = controller.progress(null, localRequest());

        assertThat(response.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    void run_whileJobRunning_returnsConflict() {
        EvalJobManager suspended = suspendedJobManager();
        EvalController controller = new EvalController(strictAuthenticator(), singleFileSuite(), suspended);

        assertThat(controller.run("secret", null, localRequest()).code()).isEqualTo(0); // 第一笔：任务挂起，running=true
        UnifiedResponse second = controller.run("secret", null, localRequest());

        assertThat(second.code()).isEqualTo(ErrorCode.CONFLICT.code());
        assertThat(second.message()).contains("评测正在进行中");
    }

    @Test
    void run_withStageFilter_runsOnlyRequestedStage() {
        // stage 过滤：真库模式单独执行红队扩展 stage，不混入 dev 全量基线
        EvalSuite suite = new EvalSuite(List.of("eval/injection.json", "eval/rag-redteam.json"));
        EvalController controller = new EvalController(
                strictAuthenticator(), suite, syncJobManager());

        UnifiedResponse response = controller.run("secret", List.of("rag-redteam"), localRequest());

        assertThat(response.code()).isEqualTo(0);
        EvalProgress progress = (EvalProgress) response.data();
        assertThat(progress.report()).isNotNull();
        assertThat(progress.report().stages()).hasSize(1);
        assertThat(progress.report().stages().get(0).stage()).isEqualTo("rag-redteam");
    }

    @Test
    void run_withUnknownStage_returnsBadRequest() {
        // 有请求项但零匹配 → BAD_REQUEST（不静默跑空集掩盖拼写错误）
        EvalController controller = new EvalController(
                strictAuthenticator(), singleFileSuite(), syncJobManager());

        UnifiedResponse response = controller.run("secret", List.of("no-such-stage"), localRequest());

        assertThat(response.code()).isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(response.data()).isNull();
    }

    @Test
    void run_nacosBackedResolver_sourceFlowsIntoReport() throws Exception {
        // 2026-09-17 Nacos 动态化端到端接线：resolver 从 Nacos 拉到内容 → source=nacos 透传 StageReport
        com.alibaba.nacos.api.config.ConfigService cs =
                org.mockito.Mockito.mock(com.alibaba.nacos.api.config.ConfigService.class);
        String nacosJson = "{\"stage\":\"injection\",\"description\":\"Nacos 版\",\"cases\":[]}";
        org.mockito.Mockito.when(cs.getConfig("agentdemo-eval-injection.json", "DEFAULT_GROUP", 3000L))
                .thenReturn(nacosJson);
        EvalJobManager manager = new EvalJobManager(executorWithTrivialPipeline(),
                new EvalContentResolver(cs, true, "DEFAULT_GROUP", "agentdemo-eval", 3000L),
                Runnable::run);
        EvalController controller = new EvalController(strictAuthenticator(), singleFileSuite(), manager);

        UnifiedResponse response = controller.run("secret", null, localRequest());

        assertThat(response.code()).isEqualTo(0);
        EvalProgress progress = (EvalProgress) response.data();
        assertThat(progress.report()).isNotNull();
        assertThat(progress.report().stages()).hasSize(1);
        assertThat(progress.report().stages().get(0).source()).isEqualTo("nacos");
        assertThat(progress.report().totalCases()).isZero(); // Nacos 版 cases 为空，证明用的不是本地文件（本地 8 例）
    }

    // ---- 2026-09-18 统一旋钮 IP 封禁（外部禁 eval）----

    private com.agentdemo007.gate.AccessGateService gateWith(boolean enabled) {
        return new com.agentdemo007.gate.AccessGateService(
                com.agentdemo007.gate.GateRule.localDefaults(enabled, "code", 5,
                        com.agentdemo007.gate.GateRule.DEFAULT_REQUIRED_MESSAGE,
                        com.agentdemo007.gate.GateRule.DEFAULT_EXHAUSTED_MESSAGE),
                java.time.Clock.systemUTC(), null, null, null);
    }

    private static HttpServletRequest externalRequest() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getRemoteAddr()).thenReturn("203.0.113.7"); // 外部 IP，无代理头
        return req;
    }

    @Test
    void run_gateEnabled_externalIp_blockedBeforeToken() {
        EvalController controller = new EvalController(strictAuthenticator(), singleFileSuite(), syncJobManager(), gateWith(true));

        UnifiedResponse response = controller.run("secret", null, externalRequest());

        assertThat(response.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(response.message()).contains("仅限本机");
    }

    @Test
    void progress_gateEnabled_externalIp_blocked() {
        EvalController controller = new EvalController(strictAuthenticator(), singleFileSuite(), syncJobManager(), gateWith(true));

        assertThat(controller.progress("secret", externalRequest()).code())
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    void run_gateDisabled_externalIp_oldTokenOnlyBehavior() {
        // 旋钮关闭（dev）：外部 IP 不封禁，行为同旧（仅令牌校验）
        EvalController controller = new EvalController(strictAuthenticator(), singleFileSuite(), syncJobManager(), gateWith(false));

        assertThat(controller.run("secret", null, externalRequest()).code()).isEqualTo(0);
    }
}
