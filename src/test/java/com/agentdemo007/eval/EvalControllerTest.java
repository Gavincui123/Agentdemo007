package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测端点控制器测试（Phase 15·T70）。
 *
 * <p>{@code POST /eval/run} 鉴权保护（验收：生产环境无鉴权不可访问 /eval/run）：
 * <ul>
 *   <li>无 token（null）→ {@link ErrorCode#UNAUTHORIZED}（code=401，data=null，不返回报告）；</li>
 *   <li>错 token → 同上 401；</li>
 *   <li>有效 token → 加载黄金集 → 跑 → {@link UnifiedResponse#success} 携 {@link EvalReport}（code=0）。</li>
 * </ul>
 *
 * <p>鉴权 seam {@link EvalAuthenticator} 注入受控实现（strict：仅 "secret" 通过）隔离真实校验；
 * {@link EvalExecutor} 注入 trivial 执行器（恒返回 ok）——控制器测试只验鉴权+加载+收口，不验通过率
 * （通过率属 {@link EvalExecutorTest} 职责）。黄金集用 {@link EvalSuite} 注入单文件 injection.json
 * （T69 已验可解析）隔离其余 stage 文件的解析耦合。
 */
class EvalControllerTest {

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

    @Test
    void run_withoutToken_returnsUnauthorized() {
        EvalController controller = new EvalController(
                executorWithTrivialPipeline(), strictAuthenticator(), singleFileSuite());

        UnifiedResponse response = controller.run(null);

        assertThat(response.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(response.data()).isNull();
    }

    @Test
    void run_withInvalidToken_returnsUnauthorized() {
        EvalController controller = new EvalController(
                executorWithTrivialPipeline(), strictAuthenticator(), singleFileSuite());

        UnifiedResponse response = controller.run("wrong-token");

        assertThat(response.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(response.data()).isNull();
    }

    @Test
    void run_withValidToken_returnsEvalReport() {
        EvalController controller = new EvalController(
                executorWithTrivialPipeline(), strictAuthenticator(), singleFileSuite());

        UnifiedResponse response = controller.run("secret");

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data()).isInstanceOf(EvalReport.class);
        EvalReport report = (EvalReport) response.data();
        assertThat(report.stages()).hasSize(1);
        assertThat(report.stages().get(0).stage()).isEqualTo("injection");
        assertThat(report.totalCases()).isGreaterThan(0);
    }
}
