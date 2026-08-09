package com.ohkb.core.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dead Letter 服务——异步任务失败记录与重试。
 * <p>
 * 当 @Async 方法（文档解析、Embedding 生成、反馈分类、通知推送）异常时，
 * 将任务信息写入 dead_letter 表，支持指数退避重试（最多 3 次）。
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final JdbcTemplate jdbc;

    public DeadLetterService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录失败任务。
     *
     * @param taskType   任务类型
     * @param taskPayload 任务参数（JSON）
     * @param error      异常信息
     */
    public void record(String taskType, String taskPayload, Throwable error) {
        String errorStack = stackTraceToString(error);
        try {
            jdbc.update("""
                INSERT INTO dead_letter (task_type, task_payload, error_message, error_stack, status)
                VALUES (?, ?::jsonb, ?, ?, 'pending')
                """, taskType, taskPayload, error.getMessage(), errorStack);
            log.warn("[DEAD-LETTER] Recorded: type={}, error={}", taskType, error.getMessage());
        } catch (Exception e) {
            log.error("[DEAD-LETTER] Failed to record dead letter!", e);
        }
    }

    /**
     * 定时重试 pending 的失败任务（指数退避，最多 3 次）。
     */
    @Scheduled(fixedDelay = 60_000) // 每 60 秒
    public void retryPendingTasks() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
            SELECT id, task_type, task_payload, retry_count, max_retries
            FROM dead_letter
            WHERE status IN ('pending', 'retrying')
              AND retry_count < max_retries
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            ORDER BY created_at
            LIMIT 10
            """);

        for (var task : pending) {
            long id = ((Number) task.get("id")).longValue();
            int retryCount = ((Number) task.get("retry_count")).intValue();
            String taskType = (String) task.get("task_type");

            // 指数退避：2^retryCount 分钟
            long delayMinutes = (long) Math.pow(2, retryCount);
            jdbc.update("""
                UPDATE dead_letter
                SET retry_count = retry_count + 1,
                    status = 'retrying',
                    next_retry_at = ?,
                    updated_at = NOW()
                WHERE id = ?
                """, Instant.now().plusSeconds(delayMinutes * 60), id);

            log.info("[DEAD-LETTER] Scheduled retry #{} for task {} (type={}), next in {}min",
                    retryCount + 1, id, taskType, delayMinutes);
        }

        // 标记超过最大重试次数的为 failed
        jdbc.update("""
            UPDATE dead_letter SET status = 'failed', updated_at = NOW()
            WHERE status IN ('pending', 'retrying')
              AND retry_count >= max_retries
            """);
    }

    /**
     * 手动解决 failed 任务。
     */
    public void resolve(long id, String resolvedBy, String note) {
        jdbc.update("""
            UPDATE dead_letter SET status = 'resolved', resolved_by = ?, resolution_note = ?, updated_at = NOW()
            WHERE id = ?
            """, resolvedBy, note, id);
        log.info("[DEAD-LETTER] Resolved: id={}, by={}", id, resolvedBy);
    }

    private String stackTraceToString(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        for (StackTraceElement ste : e.getStackTrace()) {
            sb.append("    at ").append(ste.toString()).append("\n");
        }
        if (sb.length() > 4000) {
            sb.setLength(4000); // 截断防止 DB 溢出
        }
        return sb.toString();
    }
}
