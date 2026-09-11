package com.agentdemo007.config;

import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.langgraph.GraphExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 编排模式切换·图模式装配测评。
 *
 * <p>{@code app.pipeline.mode=graph} 时 {@code LangGraphConfig} 条件装配——
 * {@link GraphExecutor} 覆盖线性 {@link PipelineOrchestrator}（后者 guard 为 mode=linear/缺省，
 * {@code matchIfMissing=true}），{@link com.agentdemo007.web.ChatController} 注入的
 * {@link PipelineExecutor} 即图执行器（④统一收口：换引擎不换出口，调用方无感）。
 *
 * <p>互斥保证：两实现不会同时为 bean——若同时存在，{@code @Autowired} 单个
 * {@link PipelineExecutor} 会抛 {@code NoUniqueBeanDefinitionException} 致上下文启动失败；
 * 故本测试成功注入且类型正确即隐含互斥（线性模式对称测见 {@link PipelineModeLinearTest}）。
 *
 * <p>RED 前提：未加 {@code LangGraphConfig} + 条件装配时 mode=graph → 图执行器 bean 不存在 →
 * 注入的仍是 {@link PipelineOrchestrator} → {@code isInstanceOf(GraphExecutor)} 断言失败。
 */
@SpringBootTest(properties = "app.pipeline.mode=graph")
class PipelineModeGraphTest {

    @Autowired
    private PipelineExecutor executor;

    @Test
    void graphMode_wiresGraphExecutor() {
        assertThat(executor).isInstanceOf(GraphExecutor.class);
    }
}
