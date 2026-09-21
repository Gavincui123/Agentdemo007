package com.agentdemo007.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会员等级装配（Phase 21）：缺省装配 mock（10086→V5、10010→V1，其余 V0）；
 * prod 接真实会员服务时注册同类型 {@link MemberLevelService} bean 即覆盖
 * （{@code @ConditionalOnMissingBean} 范式，镜像 {@code HitlConfig}）。
 */
@Configuration
public class MemberLevelConfig {

    @Bean
    @ConditionalOnMissingBean(MemberLevelService.class)
    public MemberLevelService mockMemberLevelService() {
        return new MockMemberLevelService();
    }
}
