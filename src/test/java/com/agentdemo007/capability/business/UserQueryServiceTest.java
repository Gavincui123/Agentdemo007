package com.agentdemo007.capability.business;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户查询服务测试（Slice 1·[[business-tools-workflow-dag]]）。
 *
 * <p>mock 数据须与 {@link OrderQueryServiceTest} 一致：10086 = 当前账户 gold 会员（拥有 ORD-001/002），
 * 10010 = ORD-003 持有者（非当前账户）。tier 供 T3 商品推荐场景判断活动/会员资格（用户钦定 vipLevel=gold）。
 */
class UserQueryServiceTest {

    private final UserQueryService service = new UserQueryService();

    @Test
    void findByUserId_currentUser_returnsRecord() {
        // 10086：当前账户，gold 会员（拥有 ORD-001/002）
        Optional<UserRecord> found = service.findByUserId("10086");

        assertThat(found).isPresent();
        UserRecord user = found.get();
        assertThat(user.userId()).isEqualTo("10086");
        assertThat(user.name()).isNotBlank();
        assertThat(user.tier()).isEqualTo("gold");
    }

    @Test
    void findByUserId_otherUser_returnsRecord() {
        // 10010：ORD-003 持有者（非当前账户，ORDER_NOT_OWNED 校验用）
        Optional<UserRecord> found = service.findByUserId("10010");

        assertThat(found).isPresent();
        UserRecord user = found.get();
        assertThat(user.userId()).isEqualTo("10010");
        assertThat(user.userId()).isNotEqualTo("10086");
    }

    @Test
    void findByUserId_currentUserIsGold() {
        // T3 商品推荐：gold tier 驱动会员价/活动资格
        UserRecord user = service.findByUserId("10086").orElseThrow();
        assertThat(user.tier()).isEqualTo("gold");
    }

    @Test
    void findByUserId_notFound_empty() {
        assertThat(service.findByUserId("U999")).isEmpty();
    }

    @Test
    void findByUserId_null_empty() {
        assertThat(service.findByUserId(null)).isEmpty();
    }
}
