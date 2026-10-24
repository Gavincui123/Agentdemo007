package com.agentdemo007.intent;

import com.agentdemo007.observability.AgentMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link IntentConfig} LingDT 装配测试（2026-10-24 review 收口）。
 *
 * <p>{@code lingdt.enabled=true} 而三要素（endpoint/model/api-key）缺失 → <b>fail-fast 拒绝启动</b>
 * （带病运行的表现是每轮意图识别静默回退通用小模型——「功能上线了但没上线」）；三要素齐备正常装配。
 */
class IntentConfigLingDtTest {

    private final IntentConfig config = new IntentConfig();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    @Test
    void lingDtBean_enabledWithoutConfig_failsFast() {
        LingDtProperties p = new LingDtProperties();
        p.setEnabled(true); // endpoint/model/api-key 全空

        assertThatThrownBy(() -> config.lingDtDecisionClient(p, AgentMetrics.NO_OP,
                provider(), provider(), provider()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少必填配置")
                .hasMessageContaining("LINGDT_ENDPOINT")
                .hasMessageContaining("LINGDT_MODEL")
                .hasMessageContaining("LINGDT_API_KEY");
    }

    @Test
    void lingDtBean_enabledWithConfig_returnsClient() {
        LingDtProperties p = new LingDtProperties();
        p.setEnabled(true);
        p.setEndpoint("http://lingdt.test/v1/systemone");
        p.setModel("lingdt-decision-1.0");
        p.setApiKey("test-key");

        assertThat(config.lingDtDecisionClient(p, AgentMetrics.NO_OP,
                provider(), provider(), provider())).isNotNull();
    }
}
