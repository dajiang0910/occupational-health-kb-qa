package com.ohkb.core.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 分析服务 — 定时聚合问答日志 + 看板数据。
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /**
     * 每小时聚合问答日志（@Scheduled 定时任务）。
     */
    @Scheduled(cron = "0 0 * * * *") // 每小时整点
    public void aggregateHourly() {
        log.info("[ANALYTICS] Hourly aggregation triggered");
        // Phase 2: 从 messages 表聚合 → analytics_snapshots 表
        // 统计：提问数、满意率、高频问题、响应时间分布、Token 消耗
    }

    /**
     * 看板概要数据。
     */
    public Map<String, Object> getDashboard() {
        // Phase 1: 占位数据（Phase 2: 从聚合表查询）
        return Map.of(
                "todayQuestions", 0,
                "satisfactionRate", "0%",
                "avgLatencyMs", 0,
                "cacheHitRate", "0%",
                "topQuestions", new Object[]{}
        );
    }

    /**
     * 知识盲区报告。
     */
    public Map<String, Object> getKnowledgeGaps() {
        return Map.of(
                "unansweredQuestions", 0,
                "categories", new Object[]{}
        );
    }
}
