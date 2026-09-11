package com.agentdemo007.capability.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具参数 Schema 校验器（第四层·JSON Schema 校验，手写无外部依赖）。
 *
 * <p>按 {@link ToolDefinition#params()} 逐参校验：必填缺失 / 值为 null / 类型不符 → 记错误。
 * 额外参数宽松忽略（不阻断执行）。校验失败由 {@code ToolExecutor} 包装为
 * {@link com.agentdemo007.resilience.ToolRecoverableException} 反馈 LLM 自纠正（§5.4.2）。
 */
@Component
public class SchemaValidator {

    /**
     * @param args 运行期参数（可为 null）
     * @param tool 工具定义
     * @return 校验结果
     */
    public ValidationResult validate(Map<String, Object> args, ToolDefinition tool) {
        if (args == null) {
            return ValidationResult.invalid(List.of("参数为空"));
        }
        List<ParamSpec> params = (tool.params() == null) ? List.of() : tool.params();
        List<String> errors = new ArrayList<>();
        for (ParamSpec spec : params) {
            Object value = args.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    errors.add("缺少必填参数: " + spec.name());
                }
                continue;
            }
            if (!spec.type().isInstance(value)) {
                errors.add("参数类型不符: " + spec.name()
                        + " 期望 " + spec.type().getSimpleName()
                        + " 实得 " + value.getClass().getSimpleName());
            }
        }
        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }
}
