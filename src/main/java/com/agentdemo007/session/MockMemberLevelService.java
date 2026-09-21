package com.agentdemo007.session;

import com.agentdemo007.capability.kb.KbLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 会员等级 mock 实现（Phase 21 demo 数据）：10086→V5、10010→V1、10012→V2、10013→V3、10014→V4
 * （后三者供前端身份切换器演示等级可见性阶梯，与 {@code UserQueryService} mock 用户表对齐），
 * 其余主体（含 null/空白/未知）一律 V0（fail-closed）。
 *
 * <p>demo 数据与既有 mock 用户表口径一致（{@code UserQueryTool} 的会员等级展示）；
 * 任何异常路径同样收敛 V0——等级解析失败绝不阻塞主链、绝不放大权限。
 */
public class MockMemberLevelService implements MemberLevelService {

    private static final Logger log = LoggerFactory.getLogger(MockMemberLevelService.class);

    /** demo 会员等级映射（prod 由真实会员服务实现覆盖）。 */
    private static final Map<String, KbLevel> LEVELS = Map.of(
            "10086", KbLevel.V5,
            "10010", KbLevel.V1,
            "10012", KbLevel.V2,
            "10013", KbLevel.V3,
            "10014", KbLevel.V4);

    @Override
    public KbLevel levelOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return KbLevel.V0;
        }
        try {
            return LEVELS.getOrDefault(userId.strip(), KbLevel.V0);
        } catch (Exception e) {
            log.warn("会员等级查询失败（fail-closed 按 V0）：userId={} reason={}", userId, e.getMessage());
            return KbLevel.V0;
        }
    }
}
