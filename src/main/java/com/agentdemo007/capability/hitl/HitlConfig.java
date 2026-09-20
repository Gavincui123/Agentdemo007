package com.agentdemo007.capability.hitl;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.persistence.mq.ChatTurnFinalizer;
import com.agentdemo007.persistence.repository.BizOrderRepository;
import com.agentdemo007.persistence.repository.HitlCheckpointRepository;
import com.agentdemo007.persistence.repository.HitlTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * HITL Spring 装配（Phase 11；2026-09-18 L2 挂起-恢复扩展）。
 *
 * <p>注册决策器 {@link HitlDecision}（超时阈值来自 {@code app.hitl.timeout-ms}）与两个引擎无关 seam：
 * {@link DecisionResolver}（dev=none，无带外人工决议）、{@link PermissionChecker}（dev=alwaysPermitted，无鉴权）。
 * {@link HitlHandler}/{@link HumanTicketService}/{@link HitlIdempotencyKeyResolver}/{@link HitlStep}
 * 为 {@code @Component} 自动注册。
 *
 * <p>L2 装配：{@link HitlCheckpointService}（挂起快照异步落库，{@code hitl_checkpoint} 表由 JPA
 * ddl-auto=update 自动建表；repo 缺席时内存降级）+ {@link HitlResumeService}（审批通过后异步恢复，
 * 只跑 610 后段步骤，漂移校验锚定业务幂等键）。
 *
 * <p>prod 覆盖：{@code DecisionResolver} 查工单存储的带外人工决议（随 Phase 13 持久化接入），
 * {@code PermissionChecker} 接真实鉴权（从请求上下文取用户权限）。
 */
@Configuration
public class HitlConfig {

    private static final Logger log = LoggerFactory.getLogger(HitlConfig.class);

    @Bean
    @ConditionalOnMissingBean(DecisionResolver.class)
    DecisionResolver decisionResolver() {
        log.info("未配置带外人工决议解析器，使用 none（dev：同步链路无带外决议）");
        return DecisionResolver.none();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionChecker.class)
    PermissionChecker permissionChecker() {
        log.info("未配置权限校验器，使用 alwaysPermitted（dev：无鉴权基础）");
        return PermissionChecker.alwaysPermitted();
    }

    @Bean
    HitlDecision hitlDecision(DecisionResolver resolver, PermissionChecker permissionChecker,
                               @Value("${app.hitl.timeout-ms:300000}") long timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        log.info("HITL 决策器装配：timeout={}ms", timeoutMs);
        return new HitlDecision(timeout, resolver, permissionChecker);
    }

    @Bean
    HitlCheckpointService hitlCheckpointService(ObjectProvider<HitlCheckpointRepository> repository,
                                                ObjectProvider<ObjectMapper> objectMapper,
                                                ObjectProvider<StringRedisTemplate> redisTemplate,
                                                @Value("${app.redis.enabled:false}") boolean redisEnabled,
                                                @Value("${app.hitl.checkpoint-redis-ttl:24h}") Duration redisTtl) {
        HitlCheckpointRepository repo = repository.getIfAvailable();
        ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
        // Redis 热副本随 app.redis.enabled 生效（开关关闭=环境无 Redis，内存+DB 双副本）；写路径异步，不阻塞主线
        StringRedisTemplate redis = redisEnabled ? redisTemplate.getIfAvailable() : null;
        log.info("HITL checkpoint 服务装配：DB持久化={}，Redis热副本={}（ttl={}，hitl_checkpoint 表由 JPA ddl-auto=update 或 docs/sql/hitl_l2_init.sql 建表）",
                (repo != null) ? "JPA 异步落库" : "内存降级（无 JPA 仓库）",
                (redis != null) ? "已启用" : "未启用", redisTtl);
        return new HitlCheckpointService(repo, mapper, null, redis, redisTtl);
    }

    @Bean
    HitlBusinessGate hitlBusinessGate(ObjectProvider<BizOrderRepository> orders) {
        BizOrderRepository repo = orders.getIfAvailable();
        log.info("HITL 业务前置校验门装配：订单对账={}（biz_order 表；退款查支付状态/退货查物流状态，fail-closed）",
                (repo != null) ? "已启用" : "未装配（显式跳过业务校验）");
        return new HitlBusinessGate(repo);
    }

    @Bean
    HitlResumeService hitlResumeService(HumanTicketService ticketService,
                                        HitlCheckpointService checkpointService,
                                        HitlBusinessGate businessGate,
                                        ObjectProvider<ChatTurnFinalizer> finalizer,
                                        DegradationPhraseCenter phraseCenter, AgentMetrics metrics,
                                        ObjectProvider<PipelineStep> steps, ObjectProvider<ObjectMapper> objectMapper) {
        List<PipelineStep> allSteps = steps.stream().toList();
        long postSteps = allSteps.stream().filter(s -> {
            org.springframework.core.annotation.Order o =
                    org.springframework.core.annotation.AnnotationUtils.findAnnotation(s.getClass(),
                            org.springframework.core.annotation.Order.class);
            return o != null && o.value() > HitlStep.ORDER;
        }).count();
        log.info("HITL 恢复服务装配：恢复后段步骤数={}（@Order > {}），业务前置校验=已接入", postSteps, HitlStep.ORDER);
        return new HitlResumeService(ticketService, checkpointService, finalizer.getIfAvailable(),
                phraseCenter, metrics, allSteps, objectMapper.getIfAvailable(ObjectMapper::new),
                businessGate, null);
    }
}
