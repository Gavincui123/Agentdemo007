package com.agentdemo007.eval;

/**
 * 评测端点鉴权 seam（Phase 15·T70，验收：生产环境无鉴权不可访问 /eval/run）。
 *
 * <p>校验请求携带的 token 是否允许触发批量评测。dev 默认 @Bean 读 {@code agentdemo.eval.token}
 * （默认 {@code dev-eval-token}）逐字比对；prod 覆盖为真实密钥/SSO 校验。@FunctionalInterface 便于
 * 单测注入受控实现隔离真实校验逻辑。
 *
 * <p>与 {@code ConfigWriter}/{@code RegistrationWriter} 同为引擎无关 seam：真实校验策略可插拔，
 * dev 用默认 token 不阻塞联调，prod 用环境变量/密钥中心覆盖。
 */
@FunctionalInterface
public interface EvalAuthenticator {

    boolean authenticate(String token);
}
