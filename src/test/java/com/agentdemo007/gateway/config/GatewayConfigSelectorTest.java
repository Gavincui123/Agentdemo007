package com.agentdemo007.gateway.config;

import com.agentdemo007.gateway.selector.CostAwareSelector;
import com.agentdemo007.gateway.selector.ModelSelector;
import com.agentdemo007.gateway.selector.TagBasedSelector;
import com.agentdemo007.gateway.selector.WeightBasedSelector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型选择策略工厂单测（Phase 4·可配置切换）。
 *
 * <p>验证 {@code app.model-selector.strategy} 属性驱动的策略选择：
 * tag/weight/cost 三态 + 未知/null 归一为 tag（安全默认）。
 */
class GatewayConfigSelectorTest {

    @Test
    void nullStrategy_defaultsToTag() {
        ModelSelector selector = GatewayConfig.selectModelSelector(null);
        assertThat(selector).isInstanceOf(TagBasedSelector.class);
    }

    @Test
    void tagStrategy_returnsTagBased() {
        assertThat(GatewayConfig.selectModelSelector("tag")).isInstanceOf(TagBasedSelector.class);
    }

    @Test
    void weightStrategy_returnsWeightBased() {
        assertThat(GatewayConfig.selectModelSelector("weight")).isInstanceOf(WeightBasedSelector.class);
    }

    @Test
    void costStrategy_returnsCostAware() {
        assertThat(GatewayConfig.selectModelSelector("cost")).isInstanceOf(CostAwareSelector.class);
    }

    @Test
    void unknownStrategy_defaultsToTag() {
        assertThat(GatewayConfig.selectModelSelector("nonsense")).isInstanceOf(TagBasedSelector.class);
    }

    @Test
    void strategyCaseInsensitive() {
        assertThat(GatewayConfig.selectModelSelector("WEIGHT")).isInstanceOf(WeightBasedSelector.class);
        assertThat(GatewayConfig.selectModelSelector("Cost")).isInstanceOf(CostAwareSelector.class);
    }
}
