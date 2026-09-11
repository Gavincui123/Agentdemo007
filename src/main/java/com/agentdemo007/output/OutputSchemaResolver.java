package com.agentdemo007.output;

import com.agentdemo007.intent.Intent;

/**
 * 输出结构解析器（引擎无关 seam）。
 *
 * <p>按意图解析应施加的 {@link OutputSchema}：对话类意图（闲聊/推理/长上下文）返回 {@code null}
 * （宽松，自由文本回复无需结构校验）；结构化抽取意图返回严格 JSON schema。
 * dev 落 {@link #lenient()}（始终返回 null，对话模式）；prod 覆盖为意图→schema 映射（随真实结构化场景接入）。
 */
@FunctionalInterface
public interface OutputSchemaResolver {

    /** @return 意图对应的输出结构描述；{@code null} 表示宽松模式（不校验结构）。 */
    OutputSchema resolve(Intent intent);

    /** dev 默认：始终宽松（对话模式，不施加结构校验）。 */
    static OutputSchemaResolver lenient() {
        return intent -> null;
    }
}
