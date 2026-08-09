package com.ohkb.infra.config;

/**
 * 外部集成异常（可降级，HTTP 502）。
 * 触发自动降级机制。
 */
public class IntegrationException extends RuntimeException {

    private final GlobalErrorCode errorCode;
    private final String externalService;   // bailian / wechat / pgvector

    public IntegrationException(GlobalErrorCode errorCode, String externalService, Throwable cause) {
        super(errorCode.defaultMessage() + " [" + externalService + "]", cause);
        this.errorCode = errorCode;
        this.externalService = externalService;
    }

    public IntegrationException(GlobalErrorCode errorCode, String externalService, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.externalService = externalService;
    }

    public GlobalErrorCode errorCode() {
        return errorCode;
    }

    public String externalService() {
        return externalService;
    }
}
