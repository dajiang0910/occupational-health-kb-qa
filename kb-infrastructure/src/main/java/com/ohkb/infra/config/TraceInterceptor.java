package com.ohkb.infra.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.UUID;

/**
 * 全链路追踪拦截器。
 * 每个请求自动生成 traceId 并注入 MDC，响应头回传。
 */
@Component
public class TraceInterceptor implements HandlerInterceptor {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String USER_HEADER = "X-User-Id";
    private static final String CHANNEL_HEADER = "X-Channel";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 优先使用上游传入的 traceId，否则自动生成
        String traceId = Optional.ofNullable(request.getHeader(TRACE_HEADER))
                .orElse(UUID.randomUUID().toString().substring(0, 12));

        MDC.put("traceId", traceId);
        MDC.put("userId", Optional.ofNullable(request.getHeader(USER_HEADER)).orElse("anonymous"));
        MDC.put("channel", Optional.ofNullable(request.getHeader(CHANNEL_HEADER)).orElse("unknown"));

        response.setHeader(TRACE_HEADER, traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // 防止线程池复用导致上下文污染
        MDC.clear();
    }
}
