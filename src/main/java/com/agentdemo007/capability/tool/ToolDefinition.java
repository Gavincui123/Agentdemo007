package com.agentdemo007.capability.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 工具定义（第四层·注册表的元素契约）。
 *
 * <p>聚合工具元数据（名称/描述/参数规格）与两段行为：
 * <ul>
 *   <li>{@code detector}：从用户输入判定是否触发本工具并抽取参数，命中返回 {@code Optional.of(args)}，
 *       否则 {@code empty()}。{@link ParamParser} 遍历注册表取首个命中。</li>
 *   <li>{@code executor}：以校验通过的参数执行工具，返回结果文本（写入 {@code PipelineContext.toolResults}）。
 *       抛 {@link com.agentdemo007.resilience.ToolRecoverableException} 触发 {@code ToolErrorFeedback} 自纠正。</li>
 * </ul>
 *
 * <p>强类型契约：检测器/执行器为函数式接口注入，避免反射开销与运行期脆弱性；
 * {@code @Tool} 注解仅作声明性元数据（ToolConfig 装配时据其命名/描述构建本定义）。
 */
public record ToolDefinition(
        String name,
        String description,
        List<ParamSpec> params,
        Function<String, Optional<Map<String, Object>>> detector,
        Function<Map<String, Object>, String> executor) {
}
