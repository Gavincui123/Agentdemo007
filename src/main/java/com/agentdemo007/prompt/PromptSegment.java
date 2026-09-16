package com.agentdemo007.prompt;

import java.util.Map;

/**
 * 分段式 system prompt 片段（[[segmented-systemprompt-intent-design]] 子项目 A）。
 *
 * <p>片段存于 Nacos AiService 提示词模板 key={@link com.agentdemo007.context.SystemPromptAssembler#SEGMENTS_PROMPT_KEY}
 * （内容为 bare YAML 数组），经 {@code SystemPromptAssembler} 用 SnakeYAML 解析为 {@code List<Map>}
 * 后逐条经 {@link #from(Map)} 强类型化为本 record。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code id}：片段标识，管理/审计/diff 用。</li>
 *   <li>{@code prompt}：正文纯文本，可带 {@code {{var}}} 占位（装配时渲染）。</li>
 *   <li>{@code scene}：{@code all}（全局恒带）| 粗粒度 {@link com.agentdemo007.intent.Intent#name()} | 细粒度 {@code RoutePlan.intent()}。</li>
 *   <li>{@code sort}：0-100，越大越靠前（降序拼接）。</li>
 *   <li>{@code enabled}：false 装配时跳过（热禁用不删片段）。</li>
 * </ul>
 *
 * <p>{@link #from} 容错 YAML 类型漂移（sort→Number、enabled→Boolean），缺失字段取默认
 * （{@code scene}→{@code all}、{@code sort}→0、{@code enabled}→true），保证个别片段缺字段不阻塞装配。
 */
public record PromptSegment(String id, String prompt, String scene, int sort, boolean enabled) {

    /** 默认场景：未声明 scene 的片段视为全局恒带。 */
    public static final String SCENE_ALL = "all";

    /**
     * 从 YAML 解析出的 {@code Map} 强类型化为 {@link PromptSegment}（容错默认）。
     *
     * @param m 单条片段 Map（SnakeYAML {@code load} 产物）；null→返回 null 由调用方跳过
     */
    public static PromptSegment from(Map<?, ?> m) {
        if (m == null) {
            return null;
        }
        String id = str(m.get("id"));
        String prompt = str(m.get("prompt"));
        String scene = str(m.get("scene"));
        if (scene == null || scene.isBlank()) {
            scene = SCENE_ALL;
        }
        int sort = (m.get("sort") instanceof Number n) ? n.intValue() : 0;
        boolean enabled = (m.get("enabled") instanceof Boolean b) ? b : true;
        return new PromptSegment(id, prompt, scene, sort, enabled);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
