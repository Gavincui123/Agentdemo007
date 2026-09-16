package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.UserQueryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 用户信息查询 @Tool（[[business-tools-workflow-dag]] §2.2·RUNTIME 通道）。
 *
 * <p>委托 {@link UserQueryService}（mock 数据）；结果路由进 {@code runtimeFacts}。
 * T1（实时事实）+ DAG query_user 节点 + T3 商品推荐（gold 资格）共用底层服务 mock 数据源。
 */
@Component
public class UserQueryTool {

    private final UserQueryService userService;

    public UserQueryTool(UserQueryService userService) {
        this.userService = userService;
    }

    @Tool("按用户ID查询用户信息：姓名、会员等级")
    @ToolChannel(ToolCategory.RUNTIME)
    public String queryUser(@P("用户ID，例如 10086") String userId) {
        return userService.findByUserId(userId)
                .map(u -> "用户 " + u.userId() + "：" + u.name() + "，会员等级 " + u.tier())
                .orElse("用户 " + userId + " 不存在");
    }
}
