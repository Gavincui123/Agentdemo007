package com.agentdemo007.intent;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 路由分发步骤（第三层·{@code @Order(600)}，紧随 {@code IntentRecognitionStep}）。
 *
 * <p>依据 {@code context.intent} 经 {@link RouteDispatcher} 定路由类型、{@link ModelRouter} 选模型，
 * 写入 {@code context.routeType}+{@code selectedModelId}，供 Phase 12 网关步骤构建请求。落地收口：
 * <ul>
 *   <li>有可用模型 → Proceed；</li>
 *   <li>无可用模型 → {@code ShortCircuit(MODEL_DOWN)} 话术短路（§5.12 模型路由行：无可用模型 → 话术+短路）。</li>
 * </ul>
 */
@Component
@Order(600)
public class RouteDispatchStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(RouteDispatchStep.class);

    private final RouteDispatcher dispatcher;
    private final ModelRouter router;

    public RouteDispatchStep(RouteDispatcher dispatcher, ModelRouter router) {
        this.dispatcher = dispatcher;
        this.router = router;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        Intent intent = context.intent();
        RouteRule.RouteType routeType = dispatcher.dispatch(intent);
        context.setRouteType(routeType);
        try {
            String modelId = router.route(intent, routeType);
            context.setSelectedModelId(modelId);
            log.debug("路由分发完成：sessionId={} intent={} route={} model={}",
                    context.sessionId(), intent, routeType, modelId);
            return new StepOutcome.Proceed();
        } catch (ModelSelectionException e) {
            log.warn("无可用模型，触发 MODEL_DOWN 话术短路：sessionId={} intent={} route={} reason={}",
                    context.sessionId(), intent, routeType, e.getMessage()); // 审计
            return new StepOutcome.ShortCircuit(DegradationScenario.MODEL_DOWN);
        } catch (Exception e) {
            log.warn("路由分发异常，触发 MODEL_DOWN 话术短路：sessionId={} reason={}",
                    context.sessionId(), e.getMessage(), e); // 审计
            return new StepOutcome.ShortCircuit(DegradationScenario.MODEL_DOWN);
        }
    }
}
