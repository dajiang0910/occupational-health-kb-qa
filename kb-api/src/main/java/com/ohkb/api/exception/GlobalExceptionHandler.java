package com.ohkb.api.exception;

import com.ohkb.infra.config.BizException;
import com.ohkb.infra.config.ErrorResponse;
import com.ohkb.infra.config.GlobalErrorCode;
import com.ohkb.infra.config.IntegrationException;
import com.ohkb.infra.config.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 * 按异常分层返回对应的 HTTP 状态码和错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常 — HTTP 4xx，返回错误码 + 中文描述。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBiz(BizException e) {
        log.warn("[BIZ] {}: {}", e.errorCode().code(), e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(e.errorCode(), e.getMessage(), traceId()));
    }

    /**
     * 集成异常 — HTTP 502，返回降级提示 + 触发通知。
     */
    @ExceptionHandler(IntegrationException.class)
    public ResponseEntity<ErrorResponse> handleIntegration(IntegrationException e) {
        log.error("[INTEGRATION] {} - {} - {}: {}",
                e.errorCode().code(), e.externalService(), traceId(), e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(e.errorCode(), e.getMessage(), traceId()));
    }

    /**
     * 系统异常 — HTTP 500，返回通用错误（不暴露内部细节）。
     */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ErrorResponse> handleSystem(SystemException e) {
        log.error("[SYSTEM] {} - {}: {}", e.errorCode().code(), traceId(), e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(e.errorCode(), traceId()));
    }

    /**
     * 未预期异常 — HTTP 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("[UNKNOWN] {}: {}", traceId(), e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(GlobalErrorCode.UNKNOWN_ERROR, traceId()));
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}
