package com.agentdemo007.capability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * RAG 种子语料装载器（第四层·dev 起步语料）。
 *
 * <p>启动时把客服知识库样例片段经 {@link VectorStore#index} 嵌入入向量库，使 /chat 即开即用可检索
 * （"文档向量化后正确入向量库"验收）。prod 由真实 KB 索引流程覆盖（随 LangChain4j/PG 接入）。
 * {@code app.rag.seed.enabled=false} 可关闭（如需要空库的集成测试）。
 */
@Component
@ConditionalOnProperty(name = "app.rag.seed.enabled", matchIfMissing = true)
public class RagSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagSeedRunner.class);

    private final VectorStore store;

    public RagSeedRunner(VectorStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            store.index(List.of(
                    new RagFragment("退款流程：订单7日内可申请退款，原路退回。", 0.0, "kb-refund"),
                    new RagFragment("退货政策：签收15日内可无理由退货，需包装完整。", 0.0, "kb-return"),
                    new RagFragment("物流查询：发货后3-5日送达，订单页可查物流。", 0.0, "kb-shipping"),
                    new RagFragment("会员等级：消费满千元升银卡、万元升金卡享折扣。", 0.0, "kb-member"),
                    // Phase 20·T92：精确词（订单号）种子——供 Hybrid 关键词通道精确命中测评
                    new RagFragment("订单ORD123456：商品明细与物流状态查询入口。", 0.0, "kb-order"),
                    // Phase 20·T92：历史片段种子——供时效隔离标注 + 衰减测评（过时不冒充当前）
                    new RagFragment("旧退款政策：2024年6月30日前适用的退款规则，已废止。", 0.0, "kb-historical",
                            null, Instant.parse("2024-06-30T00:00:00Z"), "HISTORICAL")));
            log.info("RAG 种子语料已索引（6 条客服知识片段，含 Phase 20 精确词/历史时效样例）");
        } catch (Exception e) {
            // ②每步降级：种子灌库依赖外部嵌入 API（可能慢/超时/不可达），失败不阻塞启动——
            // 空库/部分库时链路已有降级路径（检索空→RAG_SKIP 话术），prod 由真实 KB 索引流程覆盖
            log.warn("RAG 种子语料索引失败，跳过（不阻塞启动）：{}", e.getMessage());
        }
    }
}
