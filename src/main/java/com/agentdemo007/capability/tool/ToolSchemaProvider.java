package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolCircuitBreaker;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工具 schema + 执行器单源提供者（option A 数据步·{@code @Tool} beans → function schema + executor）。
 *
 * <p>持有 {@code @dev.langchain4j.agent.tool.Tool} 标注的工具 bean，经 LC4j {@link ToolSpecifications}
 * 反射每个 {@code @Tool} 方法生成 {@link ToolSpecification}（名称/描述/参数 schema 取自注解），
 * 同时留存 {@code (bean, method)} 绑定以构造 {@link DefaultToolExecutor}。两批同源反射，保证
 * {@link #allSchemas}/{@link #schemasFor} 的键与 {@link #executors} 的键<b>一致</b>——
 * 模型只可能调到有 executor 的工具（避免"调到工具无 executor→dispatch 跳过死工具"）。
 *
 * <p>退役映射（[[langchain4j-boot4-compat-findings]] ② Slice 3）：schema 生成 + 参数强转 + 反射调
 * 全归 LC4j（@Tool→schema、DefaultToolExecutor→JSON 解析+强转+反射），退役手撸 Detector/ParamParser/
 * SchemaValidator——[[dont-hardwrite-use-dep-methods]]：库方法优先，seam=委托非重写。
 *
 * <p>per-route 工具子集（参考系统 {@code allowed_tools_by_intent}）经 {@link #schemasFor(Collection)}
 * 按 {@code RoutePlan.required_tools} 取——模型只看到当前路由允许的 schema 子集；executors 全局
 * （任意子集内的工具均有 executor）。详见 [[routeplan-design]] + [[langchain4j-boot4-compat-findings]]。
 */
public class ToolSchemaProvider {

    private final Map<String, ToolBinding> bindingsByName;

    public ToolSchemaProvider(List<Object> toolBeans) {
        Map<String, ToolBinding> m = new LinkedHashMap<>();
        for (Object bean : toolBeans) {
            // ClassUtils.getUserClass 剥 CGLIB 代理取真实类（防 @Transactional 等代理致 getDeclaredMethods 漏方法）
            for (Method method : ClassUtils.getUserClass(bean).getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                    m.put(spec.name(), new ToolBinding(spec, bean, method));
                }
            }
        }
        this.bindingsByName = Map.copyOf(m);
    }

    /** 全部工具 schema。 */
    public List<ToolSpecification> allSchemas() {
        return bindingsByName.values().stream()
                .map(b -> b.spec)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 按 {@code RoutePlan.required_tools} 取允许子集（未知名跳过，不抛）。
     * null/空 → 空（不发 tools，普通对话/纯 RAG 场景）。
     */
    public List<ToolSpecification> schemasFor(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return toolNames.stream()
                .map(name -> bindingsByName.get(name) != null ? bindingsByName.get(name).spec : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 全局工具执行器映射（name → {@link ResilientToolExecutor} 包 {@link DefaultToolExecutor}）。
     * 与 {@link #allSchemas} 同源（同批 binding），键一致。breaker 注入后 per-tool 熔断记账。
     */
    public Map<String, ToolExecutor> executors(ToolCircuitBreaker breaker) {
        Map<String, ToolExecutor> r = new LinkedHashMap<>();
        bindingsByName.forEach((name, b) ->
                r.put(name, new ResilientToolExecutor(new DefaultToolExecutor(b.bean, b.method), breaker)));
        return r;
    }

    /** 工具绑定（schema + bean + method），同源供 schema 与 executor 构造。 */
    private record ToolBinding(ToolSpecification spec, Object bean, Method method) {}
}
