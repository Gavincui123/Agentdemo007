package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具层 Spring 装配烟测（② Slice 3 退役手撸路径后·新 LC4j 装配接线）。
 *
 * <p>旧测 {@code @Autowired} 手撸 {@code ToolExecutor}+{@code ToolRegistry} bean（已退役删除）→ 上下文必断。
 * 重写为新装配烟测：验 {@link ToolConfig} 把 {@link ToolCircuitBreaker}（{@code @Bean}）真注入
 * {@link ToolSchemaProvider#executors} 构造的 {@link ResilientToolExecutor}，{@link ToolCallExecutor} bean
 * 装配就绪，且 triangle/circle/multiplication 三个 @Tool 经反射正确拾起（按方法名，非数）。
 *
 * <p>语义证明分别归位（本测只证"Spring 把 bean 接上了"，contextLoads 不断言工具 bean）：
 * 熔断阈值计数→OPEN→{@link com.agentdemo007.resilience.ToolCircuitOpenException} 详 {@link ResilientToolExecutorTest}；
 * dispatch 语义详 {@link ToolCallExecutorTest}；schema 反射详 {@link ToolSchemaProviderTest}。
 */
@SpringBootTest
class ToolCircuitWiringTest {

    @Autowired
    private ToolCallExecutor toolCallExecutor;

    @Autowired
    private ToolSchemaProvider toolSchemaProvider;

    @Autowired
    private ToolCircuitBreaker toolCircuitBreaker;

    @Test
    void wiredBeans_toolSliceReady_elevenToolsPickedUp() {
        assertThat(toolCallExecutor).as("ToolCallExecutor bean 装配就绪").isNotNull();
        assertThat(toolCircuitBreaker).as("ToolCircuitBreaker bean 装配就绪").isNotNull();
        // [[business-tools-workflow-dag]] §2.2：4 计算 @Tool + 7 业务 @Tool（3 RUNTIME 外部系统
        // + 3 RAG 政策 + 工单进度查询 2026-09-20 提交制）= 11
        assertThat(toolSchemaProvider.allSchemas())
                .as("11 个 @Tool 均被反射拾起（4 计算 + 3 RUNTIME 外部系统 + 3 RAG 政策 + 工单查询，按方法名）")
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder(
                        "triangleArea", "circleArea", "table", "calculate",
                        "queryOrder", "queryUser", "queryProduct",
                        "queryReturnPolicy", "queryRefundPolicy", "queryPromotionPolicy",
                        "queryAfterSaleTicket");
    }
}
