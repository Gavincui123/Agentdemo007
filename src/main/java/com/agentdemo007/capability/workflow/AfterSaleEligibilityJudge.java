package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderRecord;
import com.agentdemo007.capability.business.PolicyFragment;

/**
 * 售后资格裁决 seam（生产化定案·2026-09-19 用户裁决：资格判定不能硬编码窗口规则，
 * 必须把<b>政策知识 + 订单实时事实</b>一并交给 Agent 裁决）。
 *
 * <p>退役 {@code AfterSaleValidationRule}（7 天/30 天硬编码窗口——规则与政策语料脱节、
 * 退货拒而退款受理的自相矛盾根源）；机械事实校验（订单存在/归属）非政策判断，保留在
 * validate 节点代码内，政策适用性一律交本 seam。
 *
 * <p>裁决语义三态：
 * <ul>
 *   <li>{@link Decision#ELIGIBLE}——政策支持办理 → submit + 人工审批；</li>
 *   <li>{@link Decision#INELIGIBLE}——政策明确不满足（如超无理由期限/活动商品限制）→ 业务驳回
 *       （customerMessage 为面向客户的话术，含依据）；</li>
 *   <li>{@link Decision#UNCERTAIN}——政策未覆盖/依据不足/裁决服务不可用 → fail-safe 到人工：
 *       照常 submit + 人工审批，管理员据 basis 重点复核。</li>
 * </ul>
 */
public interface AfterSaleEligibilityJudge {

    /**
     * 资格裁决输入：政策知识 + 实时事实 + 申请原词，一并交给 Agent。
     *
     * @param action         动作（REFUND / RETURN）
     * @param order          query_order 节点召回的订单实时记录（非 null——机械校验已过）
     * @param policy         query_policy 节点召回的政策片段（null 或 hit=false=政策库无条目）
     * @param userQuery      用户申请（改写后查询，缺失回退原话）
     * @param currentUserId  当前账户（归属已在机械校验通过，此处供事实呈现）
     */
    record EligibilityInput(String action, OrderRecord order, PolicyFragment policy,
                            String userQuery, String currentUserId) {
    }

    /** 裁决三态。 */
    enum Decision { ELIGIBLE, INELIGIBLE, UNCERTAIN }

    /**
     * 裁决结论。
     *
     * @param decision        三态裁决
     * @param basis           依据（政策关键句原文或「政策未覆盖/裁决不可用」说明；审计+工单展示）
     * @param customerMessage INELIGIBLE 时的面向客户驳回话术（其余为 null）
     */
    record Verdict(Decision decision, String basis, String customerMessage) {

        public static Verdict uncertain(String basis) {
            return new Verdict(Decision.UNCERTAIN, basis, null);
        }
    }

    /**
     * 裁决（<b>不抛异常</b>：LLM 不可用/输出不合法等一律降级为 {@link Decision#UNCERTAIN}，
     * fail-safe 到人工审批——资格裁决的失败绝不冒充业务驳回，也绝不盲目放行）。
     */
    Verdict judge(EligibilityInput input);
}
