package com.agentdemo007.config;

import com.agentdemo007.session.SessionLoadStep;
import com.agentdemo007.session.cache.ChatMessageCodec;
import com.agentdemo007.session.cache.InMemorySessionCacheStore;
import com.agentdemo007.session.cache.RedisSessionCacheStore;
import com.agentdemo007.session.cache.SessionCacheService;
import com.agentdemo007.session.cache.SessionCacheStore;
import com.agentdemo007.session.cache.SessionWindower;
import com.agentdemo007.session.profile.InMemoryUserProfileStore;
import com.agentdemo007.session.profile.RedisUserProfileStore;
import com.agentdemo007.session.profile.UserProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Redis 会话缓存装配（Phase 3）。
 *
 * <p>两条装配路径，互斥选其一对应后端：
 * <ul>
 *   <li>默认（dev / {@code app.redis.enabled=false}）→ {@link InMemorySessionCacheStore} 内存兜底，
 *       不依赖外部 Redis，保证应用可启动、可多轮对话。</li>
 *   <li>生产（{@code app.redis.enabled=true}）→ {@link RedisSessionCacheStore} 真实 Redis 后端，
 *       由 {@code RedisStoreConfig} 条件装配并 {@code @Primary} 覆盖。</li>
 * </ul>
 * 编排层通过 {@link SessionLoadStep}（@Order 自动收集进流水线）调用 {@link SessionCacheService}，
 * 再调用被选中的 {@link SessionCacheStore}。停 Redis 时 SessionCacheService 抛
 * {@code SessionCacheException} → SessionLoadStep 收口为 {@code SESSION_DOWN} 话术短路（HTTP 200）。
 *
 * <p><b>连接参数标准化（2026-09）</b>：host/port/password/database/timeout 由 Spring Boot
 * {@code spring.data.redis.*} 自动配置绑定（LettuceConnectionFactory/StringRedisTemplate 由 Boot 装配，
 * 不再自建）；开关 {@code app.redis.enabled} 与会话 TTL {@code app.session-cache.ttl} 为业务自定义键
 * （Spring 无标准等价）。原自定义 {@code redis.*} 前缀 / {@code RedisProperties} 已删。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    ChatMessageCodec chatMessageCodec(ObjectMapper mapper) {
        return new ChatMessageCodec(mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
    SessionCacheStore inMemorySessionCacheStore(ChatMessageCodec codec,
                                                 @Value("${app.session-cache.ttl:3600s}") Duration sessionTtl) {
        log.info("Redis 未启用，使用内存会话缓存兜底（会话不持久化）");
        return new InMemorySessionCacheStore(codec, sessionTtl);
    }

    @Bean
    SessionCacheService sessionCacheService(SessionCacheStore store,
                                             @Value("${app.session-cache.ttl:3600s}") Duration sessionTtl) {
        return new SessionCacheService(store, sessionTtl);
    }

    @Bean
    @Order(100)
    SessionLoadStep sessionLoadStep(SessionCacheService cacheService, SessionWindower windower) {
        return new SessionLoadStep(cacheService, windower);
    }

    /**
     * L1 原文窗口策略（Phase 22 T99：字符启发式轨，读侧取窗与压缩分轮共用同一实例——口径一致）。
     * 预算 {@code app.memory.window-budget-tokens}（默认 1200 tokens）× {@code chars-per-token}（默认 2.0）。
     */
    @Bean
    SessionWindower sessionWindower(@Value("${app.memory.window-budget-tokens:1200}") int windowBudgetTokens,
                                    @Value("${app.memory.chars-per-token:2.0}") double charsPerToken) {
        return new SessionWindower(windowBudgetTokens, charsPerToken);
    }

    /**
     * 用户画像存储（Phase 22 T102）：默认内存兜底（dev）；{@code app.redis.enabled=true} 时
     * 由下方 {@code UserProfileRedisConfig} 声明 {@code @Primary} Redis 哈希实现（TTL 90d 惰性续期）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
    UserProfileStore inMemoryUserProfileStore() {
        log.info("Redis 未启用，用户画像使用内存存储兜底（进程重启即失忆）");
        return new InMemoryUserProfileStore();
    }

    /**
     * 生产装配：{@code app.redis.enabled=true} 时启用真实 Redis 后端。
     *
     * <p>连接（LettuceConnectionFactory/StringRedisTemplate）由 Spring Boot 从 {@code spring.data.redis.*}
     * 自动配置提供，本类只声明会话缓存与画像的 {@code @Primary} 选择。无需自建连接工厂。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    static class RedisStoreConfig {

        @Bean
        @Primary
        SessionCacheStore redisSessionCacheStore(StringRedisTemplate redisTemplate, ChatMessageCodec codec) {
            log.info("Redis 会话缓存已启用（spring.data.redis.* 自动配置）");
            return new RedisSessionCacheStore(redisTemplate, codec);
        }

        @Bean
        @Primary
        UserProfileStore redisUserProfileStore(StringRedisTemplate redisTemplate,
                                               @Value("${app.memory.profile.ttl:90d}") Duration profileTtl) {
            log.info("Redis 用户画像存储已启用（profile:{userId} 哈希，TTL 惰性续期）");
            return new RedisUserProfileStore(redisTemplate, profileTtl);
        }
    }
}
