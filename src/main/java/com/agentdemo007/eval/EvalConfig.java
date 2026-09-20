package com.agentdemo007.eval;

import com.agentdemo007.common.pipeline.PipelineExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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

    /**
     * 评测异步作业管理器（2026-09-18 评测异步化）：daemon 单线程逐 stage 跑（真实 LLM 调用耗时长，
     * 不占 HTTP 请求线程）；POST /eval/run 秒回 + GET /eval/progress 轮询。同一时刻至多一个作业。
     */
    @Bean
    EvalJobManager evalJobManager(EvalExecutor evalExecutor, EvalContentResolver resolver) {
        return new EvalJobManager(evalExecutor, resolver, task -> {
            Thread t = new Thread(task, "eval-runner");
            t.setDaemon(true);
            t.start();
        });
    }

    @Bean
    @ConditionalOnMissingBean(EvalAuthenticator.class)
    EvalAuthenticator evalAuthenticator(
            @Value("${agentdemo.eval.token:dev-eval-token}") String expectedToken) {
        return token -> expectedToken != null && expectedToken.equals(token);
    }

    /**
     * 评测黄金集内容解析器（2026-09-17 Nacos 动态化）：{@code agentdemo.eval.source=nacos}（默认）
     * 时每次 /eval/run 实时拉 Nacos（dataId {@code <prefix>-<stage>.json}），失败/未配置逐 stage
     * 回退本地 classpath；{@code local} 恒走本地。连接参数复用 {@code spring.nacos.config.*}，
     * 构造失败降级 local-only 不阻塞启动（②每步降级）。
     */
    @Bean
    EvalContentResolver evalContentResolver(Environment env) {
        return EvalContentResolver.from(env);
    }

    @Bean
    EvalSuite evalSuite() {
        // dev-plan §Phase15 标准 8 stage 黄金集（③环节测评）+ 红队扩展 stage（rag-redteam，
        // 污染语料/召回冲突演示——真库模式经 /eval/run stage 过滤单独执行，不混入 dev 全量基线）
        return new EvalSuite(List.of(
                "eval/injection.json",
                "eval/intent.json",
                "eval/routing.json",
                "eval/rag.json",
                "eval/rag-redteam.json",
                "eval/tool.json",
                "eval/hitl.json",
                "eval/degradation.json",
                "eval/audit.json"));
    }
}
