package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全巡检·黄金数据安全不变量审计（Phase 15·T72 安全巡检）。
 *
 * <p>审计 golden 数据集编码的安全不变量（spec 级安全巡检——runtime 拦截行为由既有
 * {@code InputSecurityFilter}/{@code HitlStep} 单测覆盖；无鉴权不可访问 /eval/run 由
 * {@code EvalControllerTest} 覆盖）：
 * <ul>
 *   <li>注入攻击 100% 拦截：injection stage 中 {@code expected.scenario=INJECTION} 的用例必 {@code blocked=true}；</li>
 *   <li>HITL 高风险 100% 拦截：hitl stage 中 {@code expected.scenario=HITL_TIMEOUT} 的用例必 {@code outcome=SHORT_CIRCUIT}。</li>
 * </ul>
 *
 * <p>本测试为既有 golden 数据的 invariant 守卫（立即通过即合法回归守卫，TDD skill 允许）——
 * 守 golden spec 不被后续编辑破坏安全不变量。
 */
class SecurityAuditTest {

    private static final String INJECTION_STAGE = "eval/injection.json";
    private static final String HITL_STAGE = "eval/hitl.json";

    /** trivial 执行器仅为构造 EvalExecutor 复用 loadFile；安全审计只读 expected 字段，run 不被调用。 */
    private EvalFile load(String resource) {
        PipelineExecutor trivial = new PipelineExecutor() {
            @Override
            public PipelineResult run(PipelineContext context) {
                return PipelineResult.ok("ok");
            }
        };
        return new EvalExecutor(trivial).loadFile(resource);
    }

    @Test
    void injection_hits_allBlocked() {
        EvalFile file = load(INJECTION_STAGE);
        List<EvalCase> hits = file.cases().stream()
                .filter(c -> "INJECTION".equals(c.expected().scenario()))
                .toList();
        assertThat(hits).as("应至少有注入正例").isNotEmpty();
        for (EvalCase c : hits) {
            assertThat(c.expected().blocked())
                    .as("注入命中必须 100%% 拦截：case=%s", c.id())
                    .isTrue();
        }
    }

    @Test
    void hitl_highRisk_allShortCircuit() {
        EvalFile file = load(HITL_STAGE);
        List<EvalCase> triggers = file.cases().stream()
                .filter(c -> "HITL_TIMEOUT".equals(c.expected().scenario()))
                .toList();
        assertThat(triggers).as("应至少有 HITL 触发例").isNotEmpty();
        for (EvalCase c : triggers) {
            assertThat(c.expected().outcome())
                    .as("HITL 高风险必须 100%% 短路拦截：case=%s", c.id())
                    .isEqualTo("SHORT_CIRCUIT");
        }
    }
}
