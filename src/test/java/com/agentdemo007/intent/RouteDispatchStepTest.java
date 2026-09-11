package com.agentdemo007.intent;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 路由分发步骤测试（第三层·RouteDispatchStep @Order(600)）。
 *
 * <p>落地收口契约：依据 {@code context.intent} 经 {@link RouteDispatcher} 定路由类型、
 * {@link ModelRouter} 选模型，写入 {@code routeType}+{@code selectedModelId} + Proceed；
 * 无可用模型 → {@code ShortCircuit(MODEL_DOWN)} 话术短路（§5.12 模型路由行）。
 */
class RouteDispatchStepTest {

    private final RouteDispatcher dispatcher = mock(RouteDispatcher.class);
    private final ModelRouter router = mock(ModelRouter.class);
    private final RouteDispatchStep step = new RouteDispatchStep(dispatcher, router);

    @Test
    void success_proceeds_setsRouteTypeAndModel() {
        when(dispatcher.dispatch(Intent.REASONING)).thenReturn(RouteRule.RouteType.REASONING);
        when(router.route(Intent.REASONING, RouteRule.RouteType.REASONING)).thenReturn("gpt-4o");
        PipelineContext ctx = new PipelineContext("s1", "分析 Q3");
        ctx.setIntent(Intent.REASONING);

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.routeType()).isEqualTo(RouteRule.RouteType.REASONING);
        assertThat(ctx.selectedModelId()).isEqualTo("gpt-4o");
    }

    @Test
    void noModel_shortCircuitsModelDown() {
        when(dispatcher.dispatch(any(Intent.class))).thenReturn(RouteRule.RouteType.SIMPLE);
        when(router.route(any(Intent.class), any(RouteRule.RouteType.class)))
                .thenThrow(new ModelSelectionException("无可用模型"));
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setIntent(Intent.OTHER);

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) outcome).scenario()).isEqualTo(DegradationScenario.MODEL_DOWN);
    }

    @Test
    void name_isRouteDispatchStep() {
        assertThat(step.name()).isEqualTo("RouteDispatchStep");
    }
}
