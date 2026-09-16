package com.agentdemo007.context;

import com.agentdemo007.intent.Intent;
import com.agentdemo007.prompt.PromptRegistry;
import com.agentdemo007.prompt.PromptSegment;
import com.agentdemo007.prompt.PromptTemplate;
import com.agentdemo007.prompt.VersionSpec;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分段式 system prompt 装配器（[[segmented-systemprompt-intent-design]] 子项目 A）。
 *
 * <p>plain class（+ {@code @Bean} 在 {@link ContextConfig}，镜像 {@link ObjectiveDataLayer}/
 * {@link UserInstructionLayer} 范式）。单一职责：从 {@link PromptRegistry} 取片段清单 YAML 模板 →
 * SnakeYAML 解析 → 按 scene 选 → sort 降序 → {@code {{var}}} 渲染 → {@code "\n\n"} 拼接。
 *
 * <p>片段存于 Nacos AiService 提示词模板 key={@link #SEGMENTS_PROMPT_KEY}（内容=bare YAML 数组），
 * 经 {@link PromptRegistry#get} 取模板——热更新走 {@code NacosPromptSource} AiService gRPC push
 * （与 {@code clarify-*} 同已验证通路），本类每请求解析（清单≤22 微秒级，
 * md5 缓存由 SDK 在 registry 层内置）。
 *
 * <p>②每步降级：registry 取模板失败 / YAML 解析失败 / 匹配零片段 → 返回构造注入的 {@code fallback}
 * （= {@link SystemAnchorLayer#DEFAULT_SYSTEM_PROMPT}），不阻塞链路。
 */
public class SystemPromptAssembler {

    /** 片段清单在 PromptRegistry 中的 key（Nacos AiService 提示词模板 key）。 */
    public static final String SEGMENTS_PROMPT_KEY = "system-prompt-segments";

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)}}");

    private final PromptRegistry registry;
    private final String fallback;

    /**
     * @param registry 提示词注册中心（dev {@code LocalPromptSource} 缺 key→②降级；prod {@code NacosPromptSource} 热推）
     * @param fallback 零匹配/registry miss/解析失败时的兜底系统提示词（= {@link SystemAnchorLayer#DEFAULT_SYSTEM_PROMPT}）
     */
    public SystemPromptAssembler(PromptRegistry registry, String fallback) {
        this.registry = registry;
        this.fallback = fallback;
    }

    /**
     * 装配分段式 system prompt。
     *
     * @param coarse 粗粒度 {@link Intent}（@600 已解析；可空→只匹配 {@code all}+细）
     * @param fine   细粒度 {@code RoutePlan.intent()}（@605 已解析；可空→只匹配 {@code all}+粗）
     * @param renderVars {@code {{var}}} 渲染变量（缺失变量替换为空串）
     * @return 拼接后的系统提示词；零匹配/失败→{@code fallback}
     */
    public String assemble(Intent coarse, String fine, Map<String, String> renderVars) {
        Optional<PromptTemplate> tpl = registry.get(SEGMENTS_PROMPT_KEY, VersionSpec.latest());
        if (tpl.isEmpty()) {
            return fallback;
        }
        List<PromptSegment> segments = parse(tpl.get().template());
        if (segments.isEmpty()) {
            return fallback;
        }
        List<PromptSegment> matched = select(segments, coarse, fine);
        if (matched.isEmpty()) {
            return fallback;
        }
        matched.sort(Comparator.comparingInt(PromptSegment::sort).reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matched.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(matched.get(i).prompt());
        }
        return renderVars(sb.toString(), renderVars);
    }

    /**
     * 渲染 {@code {{var}}}：缺失变量替换为空串（区别于 {@link PromptTemplate#render} 空 vars
     * 原样返回的语义——装配产物是最终 system prompt，残留占位符会污染下游 LLM 输入）。
     */
    private String renderVars(String template, Map<String, String> vars) {
        Map<String, String> v = (vars == null) ? Map.of() : vars;
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = v.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** SnakeYAML 解析 YAML 串 → List<PromptSegment>（容错：根非 List/单条非 Map→跳过；异常→空清单）。 */
    private List<PromptSegment> parse(String yaml) {
        List<PromptSegment> out = new ArrayList<>();
        if (yaml == null || yaml.isBlank()) {
            return out;
        }
        Object root;
        try {
            root = new Yaml().load(yaml);
        } catch (Exception e) {
            return out; // 解析失败→空清单→上层 ②降级 fallback
        }
        if (!(root instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                PromptSegment seg = PromptSegment.from(m);
                if (seg != null) {
                    out.add(seg);
                }
            }
        }
        return out;
    }

    /** 选 enabled 且 scene∈{all, coarse.name(), fine} 的片段（保持清单原序，供稳定排序）。 */
    private static List<PromptSegment> select(List<PromptSegment> all, Intent coarse, String fine) {
        List<PromptSegment> matched = new ArrayList<>();
        for (PromptSegment s : all) {
            if (!s.enabled()) {
                continue;
            }
            String prompt = s.prompt();
            if (prompt == null || prompt.isBlank()) {
                continue; // 无正文片段不贡献（避免多余 \n\n）
            }
            String sc = s.scene();
            if (PromptSegment.SCENE_ALL.equals(sc)
                    || (coarse != null && sc.equals(coarse.name()))
                    || (fine != null && sc.equals(fine))) {
                matched.add(s);
            }
        }
        return matched;
    }
}
