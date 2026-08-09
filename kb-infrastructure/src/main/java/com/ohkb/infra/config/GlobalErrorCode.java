package com.ohkb.infra.config;

/**
 * 全局错误码枚举。
 * 格式：OHKB-{MODULE}-{CODE}
 * <p>
 * 参考 RFC 9457 Problem Details (application/problem+json)。
 */
public enum GlobalErrorCode {

    // ── 通用（COMMON） ──
    UNKNOWN_ERROR("OHKB-COMMON-0001", "系统内部错误，请联系管理员"),
    VALIDATION_ERROR("OHKB-COMMON-0002", "请求参数校验失败"),
    NOT_FOUND("OHKB-COMMON-0003", "请求的资源不存在"),
    METHOD_NOT_ALLOWED("OHKB-COMMON-0004", "不支持的请求方法"),

    // ── 知识库（KB） ──
    KB_DOCUMENT_PARSE_FAILED("OHKB-KB-0100", "文档解析失败"),
    KB_UNSUPPORTED_FORMAT("OHKB-KB-0101", "不支持的文件格式"),
    KB_DOCUMENT_TOO_LARGE("OHKB-KB-0102", "文件大小超出限制（最大 50MB）"),
    KB_ARTICLE_NOT_FOUND("OHKB-KB-0103", "知识条目不存在"),
    KB_IMPORT_EXCEL_INVALID("OHKB-KB-0104", "Excel 批量导入格式错误"),
    KB_VERSION_SYNC_FAILED("OHKB-KB-0105", "版本同步失败"),

    // ── 对话（CHAT） ──
    CHAT_RAG_TIMEOUT("OHKB-CHAT-0200", "RAG Pipeline 超时"),
    CHAT_CONVERSATION_NOT_FOUND("OHKB-CHAT-0201", "对话不存在"),
    CHAT_STREAM_ERROR("OHKB-CHAT-0202", "流式输出异常"),

    // ── 企业微信（WECHAT） ──
    WECHAT_SIGNATURE_INVALID("OHKB-WECHAT-0300", "回调签名校验失败"),
    WECHAT_DECRYPT_FAILED("OHKB-WECHAT-0301", "消息解密失败"),
    WECHAT_RATE_LIMITED("OHKB-WECHAT-0302", "企微接口限流"),
    WECHAT_GROUP_NOT_FOUND("OHKB-WECHAT-0303", "群配置不存在"),

    // ── 工单（TICKET） ──
    TICKET_NOT_FOUND("OHKB-TICKET-0400", "工单不存在"),
    TICKET_STATE_INVALID("OHKB-TICKET-0401", "工单状态转换非法"),
    TICKET_ALREADY_CLAIMED("OHKB-TICKET-0402", "工单已被他人认领"),

    // ── 鉴权（AUTH） ──
    AUTH_JWT_EXPIRED("OHKB-AUTH-0500", "JWT Token 已过期"),
    AUTH_JWT_MISSING("OHKB-AUTH-0501", "缺少认证 Token"),
    AUTH_JWT_INVALID("OHKB-AUTH-0502", "Token 无效"),
    AUTH_FORBIDDEN("OHKB-AUTH-0503", "无权限访问该资源"),
    AUTH_SESSION_EXPIRED("OHKB-AUTH-0504", "管理后台会话已过期，请重新登录"),

    // ── LLM（LLM） ──
    LLM_API_FAILURE("OHKB-LLM-0600", "LLM 调用失败"),
    LLM_CONSECUTIVE_FAILURES("OHKB-LLM-0601", "LLM 连续调用失败，已触发降级"),
    LLM_RATE_LIMITED("OHKB-LLM-0602", "LLM API 限流"),
    LLM_CONTEXT_OVERFLOW("OHKB-LLM-0603", "上下文 Token 超出限制"),

    // ── 文档（DOC） ──
    DOC_UNSUPPORTED_FORMAT("OHKB-DOC-0700", "不支持的文件格式"),
    DOC_PARSE_ERROR("OHKB-DOC-0701", "文档解析异常"),
    DOC_CHUNK_EMPTY("OHKB-DOC-0702", "文档分块后无有效内容"),

    // ── 限流（RATE） ──
    RATE_USER_LIMITED("OHKB-RATE-0800", "用户请求频率超过限制"),
    RATE_GROUP_LIMITED("OHKB-RATE-0801", "群消息频率超过限制"),
    RATE_GLOBAL_LIMITED("OHKB-RATE-0802", "系统繁忙，请稍后再试"),
    ;

    private final String code;
    private final String defaultMessage;

    GlobalErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
