package com.agentdemo007.eval;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 评测黄金集内容解析器（Nacos 动态化·2026-09-17 用户定案）。
 *
 * <p>每次 {@code /eval/run} 按 stage 实时拉取 Nacos（dataId：{@code <data-id-prefix>-<stage>.json}，
 * 默认 {@code agentdemo-eval-intent.json}），改测评数据不重启；Nacos 读取失败/未配置/内容为空 →
 * 逐 stage 回退本地 classpath {@code eval/<stage>.json}（本地兜底，②每步降级不杀端点）。
 * 连接参数复用 {@code spring.nacos.config.*}（与配置中心/提示词源同源，非另立一套），
 * 翻译方式与 {@code PromptSourceConfig#buildNacosProps} 同款。
 *
 * <p>{@link #from(Environment)} 为装配入口：{@code agentdemo.eval.source=local} 直连本地（不打 Nacos）；
 * ConfigService 构造失败（Nacos 不可达/缺凭据）→ 降级为 local-only resolver，不阻塞启动。
 * 解析由 {@link EvalExecutor#parseFile} 负责——本类只管「内容从哪来、哪来的」，
 * 来源随 {@link Resolved#source()} 透传至 {@link StageReport#source()}（前端可见 NACOS/LOCAL）。
 */
public class EvalContentResolver {

    private static final Logger log = LoggerFactory.getLogger(EvalContentResolver.class);

    /** 内容来源标签（随报告透传前端）。 */
    public static final String SOURCE_NACOS = "nacos";
    public static final String SOURCE_LOCAL = "local";

    /** 解析结果：内容 + 来源（nacos/local）。 */
    public record Resolved(String content, String source) {
    }

    private final ConfigService configService;
    private final boolean nacosEnabled;
    private final String group;
    private final String dataIdPrefix;
    private final long timeoutMs;

    /** 全参构造（nacosEnabled=false 或 configService=null 时恒走本地，可注入假桩单测）。 */
    public EvalContentResolver(ConfigService configService, boolean nacosEnabled,
                               String group, String dataIdPrefix, long timeoutMs) {
        this.configService = configService;
        this.nacosEnabled = nacosEnabled;
        this.group = group;
        this.dataIdPrefix = dataIdPrefix;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 装配入口：读 {@code agentdemo.eval.*} 旋钮 + {@code spring.nacos.config.*} 连接参数，
     * 构造 ConfigService（失败降级 local-only，不阻塞启动）。
     */
    public static EvalContentResolver from(Environment env) {
        String source = env.getProperty("agentdemo.eval.source", "nacos");
        String group = env.getProperty("agentdemo.eval.nacos.group", "DEFAULT_GROUP");
        String prefix = env.getProperty("agentdemo.eval.nacos.data-id-prefix", "agentdemo-eval");
        long timeoutMs = env.getProperty("agentdemo.eval.nacos.timeout-ms", Long.class, 3000L);
        boolean enabled = "nacos".equalsIgnoreCase(source);
        ConfigService service = null;
        if (enabled) {
            try {
                service = NacosFactory.createConfigService(buildNacosProps(env));
            } catch (Exception e) {
                log.warn("Nacos ConfigService 构造失败，评测黄金集降级 local-only（②每步降级）：{}", e.getMessage());
            }
        }
        return new EvalContentResolver(service, enabled && service != null, group, prefix, timeoutMs);
    }

    /**
     * 解析单个 stage 的黄金集内容：Nacos 优先（每次调用实时拉取，改数据不重启），
     * 读取失败/未配置/空内容 → 本地 classpath 兜底。永不抛——②降级端点恒可用。
     *
     * @param classpathResource 本地兜底资源路径（如 {@code eval/intent.json}），stage 名取自文件名
     */
    public Resolved resolve(String classpathResource) {
        String stage = stageOf(classpathResource);
        if (nacosEnabled) {
            String dataId = dataIdPrefix + "-" + stage + ".json";
            try {
                String content = configService.getConfig(dataId, group, timeoutMs);
                if (content != null && !content.isBlank()) {
                    return new Resolved(content, SOURCE_NACOS);
                }
                log.info("Nacos 无评测数据（dataId 不存在或为空），回退本地 classpath：dataId={}", dataId);
            } catch (Exception e) {
                log.warn("Nacos 评测数据读取失败，回退本地 classpath：dataId={} reason={}", dataId, e.getMessage());
            }
        }
        return new Resolved(readClasspath(classpathResource), SOURCE_LOCAL);
    }

    /** stage 名取自资源文件名（eval/intent.json → intent），与 {@code EvalController#stageName} 同口径。 */
    static String stageOf(String classpathResource) {
        return classpathResource.substring(classpathResource.lastIndexOf('/') + 1,
                classpathResource.lastIndexOf('.'));
    }

    private static String readClasspath(String classpathResource) {
        try (InputStream in = EvalContentResolver.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("评测资源不存在：" + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("评测资源读取失败：" + classpathResource, e);
        }
    }

    /** spring.nacos.config.* → Nacos SDK PropertyKeyConst（与 PromptSourceConfig#buildNacosProps 同款映射）。 */
    private static Properties buildNacosProps(Environment env) {
        Properties p = new Properties();
        putIfPresent(p, PropertyKeyConst.SERVER_ADDR, env.getProperty("spring.nacos.config.server-addr"));
        putIfPresent(p, PropertyKeyConst.NAMESPACE, env.getProperty("spring.nacos.config.namespace"));
        putIfPresent(p, PropertyKeyConst.USERNAME, env.getProperty("spring.nacos.config.username"));
        putIfPresent(p, PropertyKeyConst.PASSWORD, env.getProperty("spring.nacos.config.password"));
        return p;
    }

    private static void putIfPresent(Properties p, String key, String val) {
        if (val != null && !val.isBlank()) {
            p.setProperty(key, val);
        }
    }
}
