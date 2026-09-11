package com.agentdemo007.config;

import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.langgraph.GraphExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 编排模式切换·线性模式装配测评（特征/回归守卫）。
 *
 * <p>缺省（无 {@code app.pipeline.mode}）→ {@code matchIfMissing=true} → 线性
 * {@link PipelineOrchestrator} 装配，{@link GraphExecutor}（guard 为 mode=graph）不装配——
 * 注入的 {@link PipelineExecutor} 即线性编排器。这是 dev/缺省对照链路，与图模式
 * （{@link PipelineModeGraphTest}）对称互证：两实现按 mode 互斥，调用方只依赖接口无感切换。
 *
 * <p>特征测试：锁定线性分支既定不变量——mode 缺省/linear 时出口恒为线性编排器，
 * 防未来改动（如误把图实现设为缺省）悄悄破坏缺省对照链路。
 */
@SpringBootTest
class PipelineModeLinearTest {

    @Autowired
    private PipelineExecutor executor;

    @Test
    void defaultMode_wiresPipelineOrchestrator() {
        assertThat(executor).isInstanceOf(PipelineOrchestrator.class);
    }
}
