package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 评测装配（Phase 15·T70）。
 *
 * <p>{@link EvalExecutor}（plain class，T69）+ {@link EvalAuthenticator}（seam）+ {@link EvalSuite}
 * （标准 8 stage 黄金集）的 @Bean 工厂。{@link EvalController} 为 @RestController 由组件扫描拾取。
 *
 * <p>{@link EvalAuthenticator} 用 @ConditionalOnMissingBean：dev 默认逐字比对配置 token（默认
 * {@code dev-eval-token}，机制强制鉴权——无 token 即拒，满足验收"无鉴权不可访问 /eval/run"）；
 * prod 用真实密钥/SSO bean 覆盖。{@link EvalExecutor} 用 1-arg 构造走 defaultMapper
 * （已 disable FAIL_ON_UNKNOWN_PROPERTIES，忽略 eval JSON 的 context 前置字段），与单测同构。
 */
@Configuration
public class EvalConfig {

    @Bean
    EvalExecutor evalExecutor(PipelineExecutor pipelineExecutor) {
        return new EvalExecutor(pipelineExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(EvalAuthenticator.class)
    EvalAuthenticator evalAuthenticator(
            @Value("${agentdemo.eval.token:dev-eval-token}") String expectedToken) {
        return token -> expectedToken != null && expectedToken.equals(token);
    }

    @Bean
    EvalSuite evalSuite() {
        // dev-plan §Phase15 标准 8 stage 黄金集（③环节测评）
        return new EvalSuite(List.of(
                "eval/injection.json",
                "eval/intent.json",
                "eval/routing.json",
                "eval/rag.json",
                "eval/tool.json",
                "eval/hitl.json",
                "eval/degradation.json",
                "eval/audit.json"));
    }
}
