package com.agentdemo007.config;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.langgraph.GraphExecutor;
import com.agentdemo007.observability.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * LangGraph 图编排条件装配（Phase 14·编排模式切换）。
 *
 * <p>镜像 {@link RabbitMqConfig} 条件装配模式：{@code app.pipeline.mode=graph} 时启用图编排后端，
 * 注册 {@link GraphExecutor} 为 {@link PipelineExecutor} bean——与线性 {@code PipelineOrchestrator}
 * （guard 为 {@code mode=linear/缺省}）互斥，{@link com.agentdemo007.web.ChatController} 注入其一即可，
 * 对调用方无感（④统一收口：换引擎不换出口，§5.14）。
 *
 * <p>同一份 {@link PipelineStep}（Spring 按 {@code @Order} 收集）复用——图节点经 {@link GraphNode}
 * 适配同一 {@code step.process()}，两模式等价（验收：编排模式与直接链路输出结果一致）。
 * 死循环护栏 {@code maxIterations} 经 {@code app.langgraph.max-iterations} 配置（缺省 25，§5.13）。
 *
 * <p>dev 默认（{@code mode=linear/缺省}）不装配本类→无 {@link GraphExecutor} bean→线性链路为默认对照。
 */
@Configuration
@ConditionalOnProperty(name = "app.pipeline.mode", havingValue = "graph")
public class LangGraphConfig {

    private static final Logger log = LoggerFactory.getLogger(LangGraphConfig.class);

    @Bean
    PipelineExecutor graphExecutor(List<PipelineStep> steps,
                                   DegradationPhraseCenter phraseCenter,
                                   AgentMetrics metrics,
                                   @Value("${app.langgraph.max-iterations:25}") int maxIterations) {
        log.info("图编排已启用（mode=graph，节点数={}, maxIterations={}）", steps.size(), maxIterations);
        return new GraphExecutor(steps, phraseCenter, maxIterations, metrics);
    }
}
