package com.agentdemo007.web.advise;

import com.agentdemo007.common.exception.BusinessException;
import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 终端全局异常拦截：分类兜底为 {@link UnifiedResponse}，完整堆栈入日志，对用户脱敏。
 *
 * <p>当前覆盖（Phase 1 基线）：
 * <ul>
 *   <li>{@link BusinessException} — HTTP 200，业务码信封</li>
 *   <li>{@link SessionCacheException} — HTTP 503 降级</li>
 *   <li>参数校验异常（缺参/类型不匹配/约束违反）— HTTP 400</li>
 *   <li>{@link Exception} 兜底 — HTTP 500，脱敏，无 stackTrace 字段</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<UnifiedResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity.ok(UnifiedResponse.error(ex.getErrorCode()));
    }

    @ExceptionHandler(SessionCacheException.class)
    public ResponseEntity<UnifiedResponse> handleSessionCache(SessionCacheException ex) {
        log.warn("会话缓存异常：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(UnifiedResponse.error(ErrorCode.SESSION_CACHE_ERROR));
    }

    /** 参数校验异常：缺参 / 类型不匹配 / 约束违反 / 请求体校验失败，统一 HTTP 400 */
    @ExceptionHandler({
            ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<UnifiedResponse> handleValidation(Exception ex) {
        log.warn("参数校验失败：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(UnifiedResponse.error(ErrorCode.BAD_REQUEST));
    }

    /**
     * 静态资源未找到（如浏览器探测 {@code /.well-known/appspecific/com.chrome.devtools.json}）：
     * 404 + DEBUG 日志，不污染 ERROR、不暴露堆栈。
     *
     * <p>Spring Boot 4 对缺失静态资源抛 {@code NoResourceFoundException}；此前无专用 handler
     * 会落到 {@link #handleUnexpected} 记 ERROR + 500，每次浏览器开页都刷噪声。浏览器探测/路由误访
     * 属正常 404，单独收口为 DEBUG + 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<UnifiedResponse> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.debug("静态资源未找到（浏览器探测/路由误访）：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(UnifiedResponse.error(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<UnifiedResponse> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(UnifiedResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
