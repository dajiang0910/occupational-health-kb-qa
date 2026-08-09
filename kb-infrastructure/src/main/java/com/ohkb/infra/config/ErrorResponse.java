package com.ohkb.infra.config;

import java.time.Instant;

/**
 * 统一错误响应（RFC 9457 Problem Details 兼容）。
 */
public record ErrorResponse(
        String errorCode,
        String message,
        String detail,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(GlobalErrorCode code, String detail, String traceId) {
        return new ErrorResponse(code.code(), code.defaultMessage(), detail, traceId, Instant.now());
    }

    public static ErrorResponse of(GlobalErrorCode code, String traceId) {
        return new ErrorResponse(code.code(), code.defaultMessage(), null, traceId, Instant.now());
    }
}
