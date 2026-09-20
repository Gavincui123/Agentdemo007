package com.agentdemo007.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 访问闸口装配（2026-09-18 部署闸门）——{@link AccessGateService} @Bean 工厂。
 *
 * <p>{@link AccessGateFilter}/{@code GateController} 为 @Component/@RestController 组件扫描拾取。
 * 服务装配见 {@link AccessGateService#from}：yml 兜底初始值 + source=nacos（默认）时接
 * {@code agentdemo-gate.json} 热更新，Nacos 不可达降级本地值（②每步降级）。
 *
 * <p><b>配额存储选择</b>（2026-09-18 用户裁决：发布后配额防重启丢失）：{@code app.redis.enabled=true}
 * 且 {@code StringRedisTemplate} 可用 → {@link RedisGateQuotaStore}（INCR+48h TTL，重启不丢）；
 * 否则 {@link InMemoryGateQuotaStore}（dev/Redis 未启用，重启清零可接受）。
 */
@Configuration
public class GateConfig {

    private static final Logger log = LoggerFactory.getLogger(GateConfig.class);

    @Bean
    AccessGateService accessGateService(Environment env,
                                        ObjectProvider<StringRedisTemplate> redisTemplate,
                                        @Value("${app.redis.enabled:false}") boolean redisEnabled) {
        GateQuotaStore store = null;
        if (redisEnabled) {
            StringRedisTemplate redis = redisTemplate.getIfAvailable();
            if (redis != null) {
                store = new RedisGateQuotaStore(redis);
            }
        }
        log.info("访问闸口装配：配额存储={}（统一旋钮=agentdemo-gate.json enabled；localhost 豁免；eval 同旋钮封禁外部 IP）",
                (store != null) ? "Redis（重启不丢）" : "内存（重启清零）");
        return AccessGateService.from(env, store);
    }
}
