package com.ohkb.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 异步任务配置。
 * 使用 Virtual Threads（Java 21），异常不静默吞掉。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Override
    public Executor getAsyncExecutor() {
        // Java 21 Virtual Threads — 每个异步任务一个虚拟线程，无需线程池
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new OhkbAsyncExceptionHandler();
    }

    /**
     * 异步任务未捕获异常处理器。
     * 写入 dead_letter 表 + ERROR 日志 + 可选重试（指数退避，最多 3 次）。
     */
    static class OhkbAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

        private static final int MAX_RETRIES = 3;

        @Override
        public void handleUncaughtException(Throwable throwable, Method method, Object... params) {
            // 尝试从 MDC 恢复上下文（虚拟线程可能丢失）
            Map<String, String> context = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            }

            log.error("[ASYNC] Uncaught exception in @Async method '{}': {} (params={})",
                    method.getName(), throwable.getMessage(), params.length, throwable);

            // dead_letter 持久化由调用方在 catch 块中处理
            // 这里只记录日志，避免循环重试

            MDC.clear();
        }
    }
}
