package com.agentdemo007.gateway.selector;

import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.config.ModelMetadata.ModelStatus;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型选择策略测试（Phase 4·Tag/Weight/Cost 三种可插拔策略）。
 *
 * <p>每种策略对同一候选集给出不同选择结果；空候选集统一抛 {@link ModelSelectionException}。
 * WeightBased 注入可控 Random 以断言确定性边界。
 */
class ModelSelectorTest {

    private List<ModelMetadata> sampleCandidates() {
        return List.of(
                ModelMetadata.builder("a").tags(Set.of("推理")).weight(1).costPer1KTokens(0.01).build(),
                ModelMetadata.builder("b").tags(Set.of("推理")).weight(3).costPer1KTokens(0.002).build(),
                ModelMetadata.builder("c").tags(Set.of("闲聊")).weight(9).costPer1KTokens(0.05).build()
        );
    }

    // ---- TagBased ----

    @Test
    void tagBased_picksTaggedHighestWeight() {
        ModelSelector selector = new TagBasedSelector();

        ModelMetadata picked = selector.select(sampleCandidates(), SelectionCriteria.byTag("推理"));

        assertThat(picked.id()).isEqualTo("b"); // a(1) vs b(3) → b
    }

    @Test
    void tagBased_noMatch_throws() {
        ModelSelector selector = new TagBasedSelector();

        assertThatThrownBy(() -> selector.select(
                List.of(ModelMetadata.builder("a").tags(Set.of("闲聊")).build()),
                SelectionCriteria.byTag("推理")))
                .isInstanceOf(ModelSelectionException.class);
    }

    // ---- WeightBased ----

    @Test
    void weighted_drawZero_picksFirst() {
        ModelSelector selector = new WeightBasedSelector(new FixedRandom(0));

        ModelMetadata picked = selector.select(sampleCandidates(), SelectionCriteria.weighted());

        assertThat(picked.id()).isEqualTo("a"); // 权重 a=1,b=3,c=9 总13；draw=0 落 a 区间
    }

    @Test
    void weighted_drawAboveFirstBoundary_picksSecond() {
        ModelSelector selector = new WeightBasedSelector(new FixedRandom(1));

        ModelMetadata picked = selector.select(sampleCandidates(), SelectionCriteria.weighted());

        assertThat(picked.id()).isEqualTo("b"); // draw=1 越过 a(1) 边界，落 b 区间
    }

    @Test
    void weighted_singleCandidate_alwaysPicksIt() {
        ModelMetadata only = ModelMetadata.builder("solo").weight(1).build();
        ModelSelector selector = new WeightBasedSelector(new FixedRandom(0));

        assertThat(selector.select(List.of(only), SelectionCriteria.weighted()).id()).isEqualTo("solo");
    }

    // ---- CostAware ----

    @Test
    void costAware_picksCheapest() {
        ModelSelector selector = new CostAwareSelector();

        ModelMetadata picked = selector.select(sampleCandidates(), SelectionCriteria.cheapest());

        assertThat(picked.id()).isEqualTo("b"); // 0.002 最低
    }

    @Test
    void costAware_tiebreak_picksFirstCheapest() {
        ModelSelector selector = new CostAwareSelector();

        ModelMetadata picked = selector.select(List.of(
                ModelMetadata.builder("x").costPer1KTokens(0.01).build(),
                ModelMetadata.builder("y").costPer1KTokens(0.01).build()),
                SelectionCriteria.cheapest());

        assertThat(picked.id()).isEqualTo("x");
    }

    // ---- 公共：空候选集 ----

    @Test
    void any_emptyCandidates_throws() {
        assertThatThrownBy(() -> new CostAwareSelector().select(List.of(), SelectionCriteria.cheapest()))
                .isInstanceOf(ModelSelectionException.class);
    }

    @Test
    void any_disabledCandidates_throws() {
        ModelMetadata disabled = ModelMetadata.builder("d").status(ModelStatus.DISABLED).build();
        assertThatThrownBy(() -> new CostAwareSelector().select(List.of(disabled), SelectionCriteria.cheapest()))
                .isInstanceOf(ModelSelectionException.class);
    }

    /** 固定返回值 Random，断言加权边界。 */
    static final class FixedRandom extends Random {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override
        public int nextInt(int bound) {
            return value;
        }
    }
}
