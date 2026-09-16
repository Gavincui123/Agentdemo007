package com.agentdemo007.capability.tool;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 工具层 Spring 装配（Phase 9·② Slice 3 退役手撸路径后）。
 *
 * <p>装配 per-tool 断路器（{@link ToolCircuitBreaker}）、工具 schema + 执行器单源（{@link ToolSchemaProvider}，
 * @Tool beans → ToolSpecifications → schema + DefaultToolExecutor）、LC4j 前向工具调用执行器
 * （{@link ToolCallExecutor}，option A 数据步·替手撸 {@code ToolExecutor} 角色）。
 *
 * <p>退役：旧手撸 {@code ToolExecutor}（detect→parse→validate→reparse 循环）+ {@code ToolDefinition} beans
 * + Detector/ParamParser/SchemaValidator/Reparser/ToolErrorFeedback bean——模型经 function-calling 直接出
 * 结构化 {@code tool_calls}（含 JSON 参数），无关键词检测/手解析/手校验/重解析循环。schema 生成 + 参数强转
 * + 反射调归 LC4j 原生（@Tool→schema、DefaultToolExecutor→JSON 解析+强转+反射），
 * [[dont-hardwrite-use-dep-methods]]：库方法优先；[[langchain4j-boot4-compat-findings]]：seam=委托非重写。
 */
@Configuration
public class ToolConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolConfig.class);

    /**
     * Phase 17：per-tool 断路器（配置驱动阈值/冷却，系统时钟）。
     *
     * <p>阈值/冷却经 {@code app.tool.circuit.*}：失败计数达阈值 → OPEN（冷却期内拒绝，半开探针恢复）。
     * {@link ResilientToolExecutor} 在执行入口 allow-check + 成功/失败记账。
     */
    @Bean
    ToolCircuitBreaker toolCircuitBreaker(
            @Value("${app.tool.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${app.tool.circuit.cooldown-ms:30000}") long cooldownMs) {
        log.info("工具断路器装配：failureThreshold={} cooldownMs={}", failureThreshold, cooldownMs);
        return new ToolCircuitBreaker(failureThreshold, cooldownMs, System::currentTimeMillis);
    }

    /**
     * 工具 schema + 执行器单源（option A 地基）：LC4j {@code @Tool} beans
     * （triangle/circle/multiplication/arithmetic）→ ToolSpecifications → schema + DefaultToolExecutor 绑定。
     * per-route 子集按 RoutePlan.required_tools 经 {@link ToolSchemaProvider#schemasFor} 取（Slice 4）。
     * ArithmeticTool 已迁 LC4j {@code @Tool}（② Slice 3，spec name = 方法名 "calculate"）。
     */
    @Bean
    ToolSchemaProvider toolSchemaProvider(
            TriangleAreaTool triangleTool, CircleAreaTool circleTool,
            MultiplicationTool multiplicationTool, ArithmeticTool arithmeticTool,
            OrderQueryTool orderTool, UserQueryTool userTool, ProductQueryTool productTool,
            ReturnPolicyTool returnPolicyTool, RefundPolicyTool refundPolicyTool,
            PromotionPolicyTool promotionPolicyTool) {
        // [[business-tools-workflow-dag]] §2.2：6 业务 @Tool（3 RUNTIME 外部系统 + 3 RAG 政策）并入单源；
        // @ToolChannel 反射入 ToolBinding.category，ToolCallExecutor 据此标 ToolCallResult，ToolExecutionStep 路由
        return new ToolSchemaProvider(List.of(triangleTool, circleTool, multiplicationTool, arithmeticTool,
                orderTool, userTool, productTool, returnPolicyTool, refundPolicyTool, promotionPolicyTool));
    }

    /**
     * LC4j 前向工具调用执行器（option A 数据步）：单次前向 {@link com.agentdemo007.gateway.llm.GatewayChatModel}
     * .doChat(带 tools) → 模型出 {@code tool_calls} 则 {@link ResilientToolExecutor} 执行真 {@code @Tool} →
     * 结果入 {@code context.toolResults}（数据层，下游 ContextBuilder+终答步不动，收口最稳）。
     *
     * <p>工具探测=决策调用 → CHIT_CHAT 小模型 + 关思考（镜像 {@code ChatLlmService.decide}）。
     * {@code maxTokens} 复用 {@code llm.max-tokens}。specs + executors 同源（{@link ToolSchemaProvider} 单源），键一致。
     */
    @Bean
    ToolCallExecutor toolCallExecutor(UnifiedModelGateway gateway, ModelConfigCenter center,
                                     ToolSchemaProvider schemas, ToolCircuitBreaker breaker,
                                     @Value("${llm.max-tokens:1024}") int maxTokens) {
        log.info("工具调用执行器装配（LC4j 前向·option A 数据步）：specs={} maxTokens={}",
                schemas.allSchemas().size(), maxTokens);
        return new ToolCallExecutor(gateway, center, maxTokens,
                schemas.allSchemas(), schemas.executors(breaker), schemas.categoryMap());
    }
}
