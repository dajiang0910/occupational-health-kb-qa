package com.ohkb.core.rag;

import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
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
    private final AtomicInteger consecutiveProbeSuccesses = new AtomicInteger(0);
    private final int maxConsecutiveFailures;
    private final long probeIntervalSeconds;
    private final BailianClient llmClient;

    // 需要连续成功 2 次探活才恢复（防止抖动）
    private static final int PROBE_SUCCESSES_TO_RECOVER = 2;

    public DegradeMonitor(
            @Value("${app.degrade.max-consecutive-failures:3}") int maxConsecutiveFailures,
            @Value("${app.degrade.llm-probe-interval-seconds:30}") int probeIntervalSeconds,
            BailianClient llmClient
    ) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.probeIntervalSeconds = probeIntervalSeconds;
        this.llmClient = llmClient;
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
     * <p>
     * 发送轻量 LLM 请求（"ping" → expect "pong"），连续成功 2 次后恢复。
     */
    @Scheduled(fixedDelayString = "${app.degrade.llm-probe-interval-seconds:30}000")
    public void probeLlmHealth() {
        Level current = currentLevel.get();
        if (current == Level.L0) {
            consecutiveProbeSuccesses.set(0);
            return;
        }

        log.info("[DEGRADE] Probing LLM health (current level: {})", current);

        try {
            BailianClient.ChatResponse resp = llmClient.chat(
                    List.of(BailianClient.userMessage("ping（只回复 pong，不要其他内容）")),
                    5, 0.0
            );

            if (resp.content() != null && resp.content().trim().toLowerCase().contains("pong")) {
                int successes = consecutiveProbeSuccesses.incrementAndGet();
                log.info("[DEGRADE] Probe success ({}/{}): got 'pong' in {}ms",
                        successes, PROBE_SUCCESSES_TO_RECOVER, resp.latencyMs());

                if (successes >= PROBE_SUCCESSES_TO_RECOVER) {
                    Level previous = currentLevel.getAndSet(Level.L0);
                    consecutiveFailures.set(0);
                    consecutiveProbeSuccesses.set(0);
                    log.info("[DEGRADE] Recovered from {} to L0 (normal)", previous);
                }
            } else {
                consecutiveProbeSuccesses.set(0);
                log.warn("[DEGRADE] Probe returned unexpected content: {}",
                        resp.content() != null ? resp.content().substring(0, Math.min(50, resp.content().length())) : "null");
            }

        } catch (Exception e) {
            consecutiveProbeSuccesses.set(0);
            log.warn("[DEGRADE] Probe failed: {} ({} consecutive failures total)",
                    e.getMessage(), consecutiveFailures.get());
        }
    }

    /**
     * 获取连续失败次数。
     */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 获取连续探活成功次数。
     */
    public int consecutiveProbeSuccesses() {
        return consecutiveProbeSuccesses.get();
    }
}
