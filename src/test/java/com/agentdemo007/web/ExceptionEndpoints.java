package com.agentdemo007.web;

import com.agentdemo007.common.exception.BusinessException;
import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.common.response.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅用于测试：构造各类异常，验证全局异常处理器统一返回信封。
 */
@RestController
public class ExceptionEndpoints {

    @GetMapping("/exception/business")
    public void business() {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @GetMapping("/exception/session")
    public void session() {
        throw new SessionCacheException("redis down");
    }

    @GetMapping("/exception/unexpected")
    public void unexpected() {
        throw new RuntimeException("oops");
    }

    /**
     * 校验异常：显式要求 String 类型参数，调用时传数字触发类型/约束校验路径。
     */
    @GetMapping("/exception/validation")
    public String validation(@RequestParam String x) {
        return x;
    }

    /**
     * 仅用于测试：回显请求体，供 ValidationFilter（请求体大小校验）集成测试。
     */
    @PostMapping("/exception/echo")
    public String echo(@RequestBody String body) {
        return body;
    }
}
