package com.agentdemo007.capability.hitl;

import com.agentdemo007.persistence.entity.BizOrderEntity;
import com.agentdemo007.persistence.repository.BizOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * HITL 业务前置校验门（L2 挂起-恢复·2026-09-18 用户裁决：工单状态收尾不自证，结合业务）。
 *
 * <p>审批放行（决议为 APPROVED / 触发恢复执行）前对账业务系统真实状态：
 * <ul>
 *   <li><b>退款</b>（动作含 REFUND）→ 对账订单支付状态：仅 PAID 放行；UNPAID / REFUNDING /
 *       REFUNDED / 未知状态一律拒绝（fail-closed，资金动作宁可多审一次）；</li>
 *   <li><b>退货</b>（动作含 RETURN）→ 对账订单物流状态：DELIVERED / RETURN_IN_TRANSIT /
 *       RETURN_RECEIVED 放行；NOT_SHIPPED / SHIPPED 拒绝（未签收不可退货）；</li>
 *   <li>订单不存在 / 状态查询异常 → 拒绝（fail-closed，管理员修复数据后可重新决议/重驱恢复）。</li>
 * </ul>
 * 锚点解析：幂等键 {@code hitl:{action}:{entity}} 拆出动作与实体段；实体非 {@code ORD-*}
 * （摘要锚点工单）或动作非退款/退货 → 显式跳过（放行，不冒充业务校验）。
 *
 * <p>降级：{@code BizOrderRepository} 缺席（受限单测切片/未建表环境）→ 显式跳过校验并告警，
 * 不阻塞审批链路（§5.12）；生产装配由 {@code HitlConfig} 注入真实仓库。
 * 查询为管理链路同步单点查（主键查，非主线），不在用户对话主链路上。
 */
public class HitlBusinessGate {

    private static final Logger log = LoggerFactory.getLogger(HitlBusinessGate.class);

    private static final String ORDER_ENTITY_PREFIX = "ORD-";

    /**
     * 业务锚点幂等键前缀白名单：{@code hitl:{action}:{entity}}（HITL 检查点单）与
     * {@code wfa:{action}:{entity}}（售后工作流审批单·生产化 2026-09-19）同形解析，
     * 工作流审批单决议前同样过业务对账（退款查支付状态/退货查物流状态）。
     */
    private static final java.util.List<String> KEY_PREFIXES =
            java.util.List.of("hitl:", "wfa:");

    private final BizOrderRepository orders; // 可空：无 JPA 环境显式跳过

    public HitlBusinessGate(BizOrderRepository orders) {
        this.orders = orders;
    }

    /** 宽松实例（兼容构造/部分单测）：永远放行，仅做形态校验不做业务对账。 */
    public static HitlBusinessGate permissive() {
        return new HitlBusinessGate(null);
    }

    /** 校验结论：{@code allowed=false} 时 {@code reason} 为面向管理员的业务原因（如实、可操作）。 */
    public record Decision(boolean allowed, String reason) {

        public static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        public static Decision block(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * 工单审批/恢复前的业务对账。幂等键无业务锚点或动作不涉资金/物流时显式跳过。
     */
    public Decision check(HumanTicket ticket) {
        if (ticket == null) {
            return Decision.block("工单不存在");
        }
        String key = ticket.idempotencyKey();
        if (orders == null) {
            return Decision.allow("业务校验未装配（无订单库），跳过");
        }
        String matchedPrefix = keyPrefixOf(key);
        if (matchedPrefix == null) {
            return Decision.allow("工单无业务幂等键，跳过业务校验");
        }
        String rest = key.substring(matchedPrefix.length());
        int sep = rest.indexOf(':');
        String action = upper(sep < 0 ? rest : rest.substring(0, sep));
        String entity = sep < 0 ? "" : rest.substring(sep + 1);
        if (!entity.startsWith(ORDER_ENTITY_PREFIX)) {
            return Decision.allow("无订单实体（摘要锚点工单），跳过业务校验");
        }
        boolean refund = action.contains("REFUND");
        boolean ret = action.contains("RETURN");
        if (!refund && !ret) {
            return Decision.allow("动作 " + action + " 不涉退款/退货，跳过业务校验");
        }

        BizOrderEntity order;
        try {
            order = orders.findById(entity).orElse(null);
        } catch (Exception e) {
            log.warn("HITL 业务对账查询失败（fail-closed）：entity={} reason={}", entity, e.getMessage()); // 审计
            return Decision.block("订单状态查询失败，请稍后重试：" + e.getMessage());
        }
        if (order == null) {
            return Decision.block("订单不存在：" + entity + "（请核对订单号或补录订单数据）");
        }
        return refund ? checkRefund(entity, order) : checkReturn(entity, order);
    }

    /** 退款对账：以支付状态为前提（用户裁决示例口径）。 */
    private Decision checkRefund(String entity, BizOrderEntity order) {
        String pay = upper(order.getPaymentStatus());
        return switch (pay) {
            case "PAID" -> Decision.allow("订单 " + entity + " 已支付，满足退款前提");
            case "UNPAID" -> Decision.block("订单 " + entity + " 未支付，不满足退款前提");
            case "REFUNDING" -> Decision.block("订单 " + entity + " 退款处理中，勿重复决议放行");
            case "REFUNDED" -> Decision.block("订单 " + entity + " 已退款，不可重复退款");
            default -> Decision.block("订单 " + entity + " 支付状态异常（" + order.getPaymentStatus() + "），拒绝放行");
        };
    }

    /** 退货对账：以物流状态为前提（用户裁决示例口径）。 */
    private Decision checkReturn(String entity, BizOrderEntity order) {
        String logistics = upper(order.getLogisticsStatus());
        return switch (logistics) {
            case "DELIVERED", "RETURN_IN_TRANSIT", "RETURN_RECEIVED" ->
                    Decision.allow("订单 " + entity + " 已签收/退货在途，满足退货前提");
            case "NOT_SHIPPED" -> Decision.block("订单 " + entity + " 尚未发货，不满足退货前提");
            case "SHIPPED" -> Decision.block("订单 " + entity + " 在途，签收后方可退货");
            default -> Decision.block("订单 " + entity + " 物流状态异常（" + order.getLogisticsStatus() + "），拒绝放行");
        };
    }

    /** 命中的业务锚点前缀（{@code hitl:}/{@code wfa:}）；无业务锚点键返回 null（显式跳过）。 */
    private static String keyPrefixOf(String key) {
        if (key == null) {
            return null;
        }
        for (String prefix : KEY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private static String upper(String s) {
        return (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
