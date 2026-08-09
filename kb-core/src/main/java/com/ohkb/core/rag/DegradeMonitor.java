package com.ohkb.core.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 降级监控器。
 * <p>
 * 自动检测：连续 N 次 LLM 调用失败 → 触发降级<br>
 * 自动恢复：每 30 秒探活一次，LLM 恢复后切回正常模式
 */
@Component
public class DegradeMonitor {

    private static final Logger log = LoggerFactory.getLogger(DegradeMonitor.class);

    /**
     * 降级级别。
     */
    public enum Level {
        L0,  // 正常：LLM + RAG 全功能
        L1,  // LLM 不可用：纯检索模式
        L2,  // 检索不可用：仅语义缓存
        L3   // 全部不可用：降级消息
    }

    private final AtomicReference<Level> currentLevel = new AtomicReference<>(Level.L0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int maxConsecutiveFailures;
    private final long probeIntervalSeconds;

    public DegradeMonitor(
            @Value("${app.degrade.max-consecutive-failures:3}") int maxConsecutiveFailures,
            @Value("${app.degrade.llm-probe-interval-seconds:30}") int probeIntervalSeconds
    ) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.probeIntervalSeconds = probeIntervalSeconds;
    }

    /**
     * 记录 LLM 调用成功。
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        // 如果当前是降级状态，尝试恢复
        Level current = currentLevel.get();
        if (current != Level.L0) {
            currentLevel.set(Level.L0);
            log.info("[DEGRADE] Recovered to L0 (normal)");
        }
    }

    /**
     * 记录 LLM 调用失败，返回是否需要触发降级。
     */
    public boolean recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= maxConsecutiveFailures) {
            Level current = currentLevel.get();
            if (current == Level.L0) {
                currentLevel.set(Level.L1);
                log.error("[DEGRADE] LLM consecutive failures={}, degrading to L1 (retrieval-only)",
                        failures);
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前降级级别。
     */
    public Level currentLevel() {
        return currentLevel.get();
    }

    /**
     * 是否处于降级状态。
     */
    public boolean isDegraded() {
        return currentLevel.get() != Level.L0;
    }

    /**
     * 定时探活：每 30 秒检测 LLM API 是否恢复。
     */
    @Scheduled(fixedDelayString = "${app.degrade.llm-probe-interval-seconds:30}000")
    public void probeLlmHealth() {
        Level current = currentLevel.get();
        if (current == Level.L0) return;

        log.debug("[DEGRADE] Probing LLM health (current level: {})", current);
        // Phase 2: 实际探活 — 发送轻量 LLM 请求（"ping" → expect "pong"）
        // 如果恢复：currentLevel.set(Level.L0); log.info("Recovered to L0");
    }
}
