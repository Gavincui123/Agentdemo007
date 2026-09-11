package com.agentdemo007.web.advise;

import com.agentdemo007.common.response.UnifiedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态资源 404 收口测试（Chrome DevTools 探测 /.well-known/... 噪声修复）。
 *
 * <p>此前 NoResourceFoundException 无专用 handler→落到 handleUnexpected 记 ERROR+500；
 * 修复后须 404（浏览器探测/路由误访属正常 404，不污染 ERROR 日志、不暴露堆栈）。
 */
class GlobalExceptionHandlerNoResourceTest {

    @Test
    void noResourceFound_returns404_notInternalServerError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        String path = "/.well-known/appspecific/com.chrome.devtools.json";
        // Spring 7（Framework 7.0）构造器签名：(HttpMethod, resourcePath, message)
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, path, "No static resource " + path);

        ResponseEntity<UnifiedResponse> resp = handler.handleNoResourceFound(ex);

        // 关键断言：404 而非 500（不再误报为"未预期异常"）
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
    }
}
