package com.agentdemo007.gate;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 访问闸口服务（2026-09-18 部署闸门）：规则热更新 + 按 IP 每日轮次额度。
 *
 * <p><b>规则来源</b>：本地 yml 兜底初始值 → Nacos dataId {@code agentdemo-gate.json} 热更新
 * （启动读一次 + Listener 推送，改开关/口令/限额秒级生效不重启）。Nacos 不可达→降级本地值继续
 * （闸口本身②降级：fail-open 保可用，后端各端点自有鉴权不受影响）。配置解析失败→保留当前生效值
 * （宁用旧值不拒服务）。
 *
 * <p><b>额度</b>：{@code ip|yyyy-MM-dd}（Asia/Shanghai）原子计数，超限拒绝；存储经
 * {@link GateQuotaStore} seam——内存（重启清零，dev）或 Redis（重启不丢，发布口径，
 * {@code app.redis.enabled=true} 时装配）。<b>localhost 豁免</b>：本机直连（无代理头且
 * remoteAddr 回环）不经闸口——统一旋钮只对外部 IP 生效（用户裁决：除 localhost 外所有外部
 * IP 每日 5 轮）。常驻内存 O(活 IP 数)，超阈值剪枝过期日期键（内存实现）。
 *
 * <p>口令比对用 {@link MessageDigest#isEqual} 常量时间比较；IP 解析 <b>X-Real-IP（nginx
 * {@code proxy_set_header X-Real-IP $remote_addr} 覆写客户端伪造值，可信）</b> → X-Forwarded-For
 * 第一跳 → remoteAddr（2026-09-18 顺序修正：原 XFF 优先可被请求头伪造刷额度）。plain class +
 * @Bean 工厂（{@code GateConfig}），可直构单测。
 */
public class AccessGateService {

    private static final Logger log = LoggerFactory.getLogger(AccessGateService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper MAPPER =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final AtomicReference<GateRule> rule;
    private final Clock clock;
    private final GateQuotaStore quotaStore;

    /** 兼容构造（既有测试/降级）：内存配额存储（重启清零）。 */
    public AccessGateService(GateRule initial, Clock clock, ConfigService nacos, String dataId, String group) {
        this(initial, clock, nacos, dataId, group, new InMemoryGateQuotaStore(clock));
    }

    /** 全参构造：配额存储可注入（发布口径=Redis）。 */
    public AccessGateService(GateRule initial, Clock clock, ConfigService nacos, String dataId, String group,
                             GateQuotaStore quotaStore) {
        this.rule = new AtomicReference<>(initial);
        this.clock = clock;
        this.quotaStore = (quotaStore != null) ? quotaStore : new InMemoryGateQuotaStore(clock);
        if (nacos == null) {
            return;
        }
        try {
            String initialNacos = nacos.getConfig(dataId, group, 3000);
            if (initialNacos != null && !initialNacos.isBlank()) {
                handleConfig(initialNacos);
            }
            nacos.addListener(dataId, group, new com.alibaba.nacos.api.config.listener.Listener() {
                @Override
                public java.util.concurrent.Executor getExecutor() {
                    return null; // SDK 内部默认执行器
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    handleConfig(configInfo);
                }
            });
            log.info("访问闸口配置已接入 Nacos 热更新：dataId={} group={}", dataId, group);
        } catch (Exception e) {
            log.warn("访问闸口 Nacos 接入失败，使用本地兜底配置（②每步降级）：{}", e.getMessage());
        }
    }

    /** Nacos 配置回调（包级 seam 供单测直接驱动）：解析失败保留当前配置。 */
    void handleConfig(String json) {
        try {
            GateRule parsed = MAPPER.readValue(json, GateRule.class);
            GateRule merged = parsed.normalize(rule.get());
            rule.set(merged);
            log.info("访问闸口配置已热更新：enabled={} dailyLimit={}", merged.enabled(), merged.dailyLimit());
        } catch (Exception e) {
            log.warn("访问闸口配置解析失败，保留当前生效配置：reason={}", e.getMessage());
        }
    }

    /** 当前生效规则（闸口过滤器/控制器实时读取）。 */
    public GateRule current() {
        return rule.get();
    }

    /** 原子计数尝试一次对话：额度内→true（计数+1）；超限→false。存储故障→true（fail-open，不打死入口）。 */
    public boolean tryAcquire(String ip, int limit) {
        try {
            return quotaStore.incrementAndGet(key(ip)) <= limit;
        } catch (Exception e) {
            log.warn("闸口配额计数失败（fail-open 直通）：ip={} reason={}", ip, e.getMessage());
            return true;
        }
    }

    /** 今日剩余额度（不消耗；登录接口展示用）。 */
    public int remaining(String ip, int limit) {
        int used;
        try {
            used = quotaStore.peek(key(ip));
        } catch (Exception e) {
            log.warn("闸口配额读取失败（按 0 已用展示）：ip={} reason={}", ip, e.getMessage());
            used = 0;
        }
        return Math.max(0, limit - used);
    }

    /**
     * 口令比对（常量时间；期望口令未配置一律不匹配——fail-closed 暴露配置遗漏）。
     */
    public static boolean codeMatches(String given, String expected) {
        if (given == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 客户端 IP：<b>X-Real-IP（nginx 覆写，可信）</b>→ X-Forwarded-For 第一跳 → remoteAddr。
     * 2026-09-18 顺序修正：原 XFF 优先可被客户端伪造（每请求换一个假 XFF 即得无限新鲜额度）。
     */
    public static String clientIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(real)) {
            return real.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 是否本机直连/本机来源（统一旋钮豁免口径：localhost 不经口令与额度，2026-09-18 用户裁决）。
     * 无代理头 → 看 TCP 对端 remoteAddr（真本机直连）；有代理头 → 看解析出的客户端 IP
     * （X-Real-IP 由 nginx 以 $remote_addr 覆写，伪造值不生效——信任链以 nginx 配置为前提，见 DEPLOY）。
     */
    public static boolean isLoopbackClient(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        String xff = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(real) && !StringUtils.hasText(xff)) {
            return isLoopbackIp(request.getRemoteAddr());
        }
        return isLoopbackIp(clientIp(request));
    }

    private static boolean isLoopbackIp(String ip) {
        if (ip == null) {
            return false;
        }
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private String key(String ip) {
        return ip + "|" + LocalDate.now(clock);
    }

    /** Spring 装配入口：yml 兜底初始值 + source=nacos（默认）时接 Nacos 热更新（内存配额）。 */
    public static AccessGateService from(Environment env) {
        return from(env, null);
    }

    /** Spring 装配入口（指定配额存储；发布口径传 Redis 实现，null=内存）。 */
    public static AccessGateService from(Environment env, GateQuotaStore quotaStore) {
        GateRule local = GateRule.localDefaults(
                env.getProperty("agentdemo.gate.enabled", Boolean.class, false),
                env.getProperty("agentdemo.gate.access-code", ""),
                env.getProperty("agentdemo.gate.daily-limit", Integer.class, 5),
                env.getProperty("agentdemo.gate.required-message", GateRule.DEFAULT_REQUIRED_MESSAGE),
                env.getProperty("agentdemo.gate.exhausted-message", GateRule.DEFAULT_EXHAUSTED_MESSAGE));
        Clock clock = Clock.system(ZONE);
        if (!"nacos".equalsIgnoreCase(env.getProperty("agentdemo.gate.source", "nacos"))) {
            return new AccessGateService(local, clock, null, null, null, quotaStore);
        }
        try {
            ConfigService cs = NacosFactory.createConfigService(buildNacosProps(env));
            return new AccessGateService(local, clock, cs,
                    env.getProperty("agentdemo.gate.data-id", "agentdemo-gate.json"),
                    env.getProperty("agentdemo.gate.group", "DEFAULT_GROUP"), quotaStore);
        } catch (Exception e) {
            log.warn("Nacos ConfigService 构造失败，访问闸口降级本地配置（②每步降级）：{}", e.getMessage());
            return new AccessGateService(local, clock, null, null, null, quotaStore);
        }
    }

    /** spring.nacos.config.* → Nacos SDK PropertyKeyConst（与 PromptSourceConfig/EvalContentResolver 同款映射）。 */
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
