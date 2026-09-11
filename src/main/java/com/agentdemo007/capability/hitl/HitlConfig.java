package com.agentdemo007.capability.hitl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * HITL Spring 装配（Phase 11）。
 *
 * <p>注册决策器 {@link HitlDecision}（超时阈值来自 {@code app.hitl.timeout-ms}）与两个引擎无关 seam：
 * {@link DecisionResolver}（dev=none，无带外人工决议）、{@link PermissionChecker}（dev=alwaysPermitted，无鉴权）。
 * {@link HitlHandler}/{@link HumanTicketService}/{@link HitlStep} 为 {@code @Component} 自动注册，
 * 注入 {@link HitlDecision} bean。
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
}
