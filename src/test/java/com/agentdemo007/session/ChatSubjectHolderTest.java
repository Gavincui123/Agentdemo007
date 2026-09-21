package com.agentdemo007.session;

import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyFragment;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.capability.kb.KbLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatSubject} / {@link ChatSubjectHolder} 单测（Phase 21 等级门·@Tool 通道桥接）：
 * holder 未设置/清除后收敛 ANONYMOUS（fail-closed）、null 归一、seam 2 参兼容口径 = 匿名主体。
 */
class ChatSubjectHolderTest {

    @AfterEach
    void cleanUp() {
        ChatSubjectHolder.clear(); // 防同线程残留串测
    }

    @Test
    void holderDefaultsToAnonymous_andClearRestoresIt() {
        assertThat(ChatSubjectHolder.current()).isEqualTo(ChatSubject.ANONYMOUS);

        ChatSubjectHolder.set(ChatSubject.of("10086", KbLevel.V5));
        assertThat(ChatSubjectHolder.current().userId()).isEqualTo("10086");
        assertThat(ChatSubjectHolder.current().memberLevel()).isEqualTo(KbLevel.V5);

        ChatSubjectHolder.clear();
        assertThat(ChatSubjectHolder.current()).isEqualTo(ChatSubject.ANONYMOUS); // 防线程池串号
    }

    @Test
    void nullSubjectAndNullLevelNormalizeToFailsClosedV0() {
        ChatSubjectHolder.set(null);
        assertThat(ChatSubjectHolder.current()).isEqualTo(ChatSubject.ANONYMOUS);

        assertThat(ChatSubject.of("10010", null).memberLevel()).isEqualTo(KbLevel.V0); // 等级缺省 V0
        assertThat(ChatSubject.of(null, KbLevel.V3).userId()).isNull();                // 匿名可带档（eval 场景）
        assertThat(ChatSubject.ANONYMOUS.memberLevel()).isEqualTo(KbLevel.V0);
    }

    @Test
    void seamTwoArgCompatibilityDefaultsToAnonymousSubject() {
        // seam 既有 2 参口径 = 匿名主体（fail-closed：缺主体的调用方宁可少给不可越级）
        AtomicReference<ChatSubject> seen = new AtomicReference<>();
        PolicyQueryService seam = new PolicyQueryService() {
            @Override
            public PolicyFragment query(PolicyDomain domain, String query, ChatSubject subject) {
                seen.set(subject);
                return null;
            }
        };

        seam.query(PolicyDomain.REFUND, "退款多久到账");

        assertThat(seen.get()).isEqualTo(ChatSubject.ANONYMOUS);
        assertThat(seen.get().memberLevel()).isEqualTo(KbLevel.V0);
    }
}
