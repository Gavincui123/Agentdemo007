package com.agentdemo007.session;

import com.agentdemo007.capability.kb.KbLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MockMemberLevelService} 单测（Phase 21 T94）：demo 映射 10086→V5、10010→V1；
 * 未知/空主体一律 V0（fail-closed），任何失败路径不抛异常。
 */
class MockMemberLevelServiceTest {

    private final MockMemberLevelService service = new MockMemberLevelService();

    @Test
    void demoUsersResolveToConfiguredLevels() {
        assertThat(service.levelOf("10086")).isEqualTo(KbLevel.V5);
        assertThat(service.levelOf("10010")).isEqualTo(KbLevel.V1);
    }

    @Test
    void unknownOrBlankOrNullOrEmptyFailsClosedToV0() {
        assertThat(service.levelOf("99999")).isEqualTo(KbLevel.V0);
        assertThat(service.levelOf("")).isEqualTo(KbLevel.V0);
        assertThat(service.levelOf("   ")).isEqualTo(KbLevel.V0);
        assertThat(service.levelOf(null)).isEqualTo(KbLevel.V0);
    }
}
