package com.ohkb.core.common;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 三层限流服务。
 * <p>
 * 用户级：Guava RateLimiter（per userId）<br>
 * 群级：Guava RateLimiter（per groupId）<br>
 * 全局级：Guava RateLimiter（单例）
 */
@Component
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final ConcurrentHashMap<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> groupLimiters = new ConcurrentHashMap<>();
    private final RateLimiter globalLimiter;

    private final double userPermitsPerSecond;
    private final double groupPermitsPerSecond;

    public RateLimiterService(
            @Value("${app.wechat.rate-limit.user-per-minute:3}") double userPerMinute,
            @Value("${app.wechat.rate-limit.group-per-minute:10}") double groupPerMinute,
            @Value("${app.wechat.rate-limit.global-qps:50}") double globalQps
    ) {
        this.userPermitsPerSecond = userPerMinute / 60.0;
        this.groupPermitsPerSecond = groupPerMinute / 60.0;
        this.globalLimiter = RateLimiter.create(globalQps);
    }

    /**
     * 检查用户级限流。返回 true 表示允许，false 表示限流触发。
     */
    public boolean tryAcquireUser(String userId) {
        RateLimiter limiter = userLimiters.computeIfAbsent(userId,
                k -> RateLimiter.create(userPermitsPerSecond));
        boolean allowed = limiter.tryAcquire();
        if (!allowed) {
            log.warn("[RATE] User throttled: userId={}", userId);
        }
        return allowed;
    }

    /**
     * 检查组级限流。
     */
    public boolean tryAcquireGroup(String groupId) {
        RateLimiter limiter = groupLimiters.computeIfAbsent(groupId,
                k -> RateLimiter.create(groupPermitsPerSecond));
        boolean allowed = limiter.tryAcquire();
        if (!allowed) {
            log.warn("[RATE] Group throttled: groupId={}", groupId);
        }
        return allowed;
    }

    /**
     * 检查全局限流。
     */
    public boolean tryAcquireGlobal() {
        boolean allowed = globalLimiter.tryAcquire();
        if (!allowed) {
            log.warn("[RATE] Global throttled");
        }
        return allowed;
    }
}
