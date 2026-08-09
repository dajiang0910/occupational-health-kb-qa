package com.ohkb.infra.config;

/**
 * 系统内部异常（不可恢复，HTTP 500）。
 * 不暴露内部细节给客户端。
 */
public class SystemException extends RuntimeException {

    private final GlobalErrorCode errorCode;

    public SystemException(GlobalErrorCode errorCode, Throwable cause) {
        super(errorCode.defaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public SystemException(GlobalErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public GlobalErrorCode errorCode() {
        return errorCode;
    }
}
