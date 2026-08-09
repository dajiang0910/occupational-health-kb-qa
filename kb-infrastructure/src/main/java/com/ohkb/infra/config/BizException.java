package com.ohkb.infra.config;

/**
 * 业务异常（可恢复，HTTP 4xx）。
 */
public class BizException extends RuntimeException {

    private final GlobalErrorCode errorCode;

    public BizException(GlobalErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(GlobalErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BizException(GlobalErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public GlobalErrorCode errorCode() {
        return errorCode;
    }
}
