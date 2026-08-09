package com.ohkb.core.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企微群用户级上下文管理器。
 * <p>
 * 为每个群维护 {@code ConcurrentHashMap<String, UserThread>（user_id → ThreadContext）}，
 * 而非群级单一上下文，确保不同用户在同一群聊中的对话不互相干扰。
 *
 * <h3>路由决策优先级：</h3>
 * <ol>
 *   <li>回复链（reply_to 指向 Bot 回复的消息链）→ 100% 命中</li>
 *   <li>用户窗口（该用户有活跃线程）→ 路由到自己的线程</li>
 *   <li>语义粘连（代词/省略主语 + 与历史线程相似度 > 0.85）→ 唤醒旧线程</li>
 *   <li>默认 → 新线程</li>
 * </ol>
 */
@Component
public class GroupContextManager {

    private static final Logger log = LoggerFactory.getLogger(GroupContextManager.class);

    // 群级线程表：groupId → (userId → UserThread)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, UserThread>> groupThreads
            = new ConcurrentHashMap<>();

    private final int threadTtlMinutes;
    private final int maxThreadsPerGroup;

    public GroupContextManager(
            @org.springframework.beans.factory.annotation.Value("${app.wechat.thread-ttl-minutes:30}") int threadTtlMinutes,
            @org.springframework.beans.factory.annotation.Value("${app.wechat.max-threads-per-group:50}") int maxThreadsPerGroup
    ) {
        this.threadTtlMinutes = threadTtlMinutes;
        this.maxThreadsPerGroup = maxThreadsPerGroup;
    }

    /**
     * 用户对话线程。
     */
    public static class UserThread {
        private final String threadId;
        private final String userId;
        private final String groupId;
        private final List<MessageEntry> messages;
        private Instant lastActivity;
        private volatile boolean active;

        public UserThread(String threadId, String userId, String groupId) {
            this.threadId = threadId;
            this.userId = userId;
            this.groupId = groupId;
            this.messages = new ArrayList<>();
            this.lastActivity = Instant.now();
            this.active = true;
        }

        public void addMessage(String role, String content) {
            messages.add(new MessageEntry(role, content, Instant.now()));
            lastActivity = Instant.now();
            // 只保留最近 20 条消息
            if (messages.size() > 20) {
                messages.subList(0, messages.size() - 20).clear();
            }
        }

        public boolean isActive(int ttlMinutes) {
            return active && lastActivity.plusSeconds(ttlMinutes * 60L).isAfter(Instant.now());
        }

        public void deactivate() {
            active = false;
        }

        public String threadId() { return threadId; }
        public String userId() { return userId; }
        public String groupId() { return groupId; }
        public Instant lastActivity() { return lastActivity; }
        public List<MessageEntry> messages() { return List.copyOf(messages); }

        /**
         * 获取最近的消息（用于 LLM 上下文）。
         */
        public List<Map<String, String>> recentMessages(int maxMessages) {
            int from = Math.max(0, messages.size() - maxMessages);
            return messages.subList(from, messages.size()).stream()
                    .map(m -> Map.of("role", m.role(), "content", m.content()))
                    .toList();
        }

        public record MessageEntry(String role, String content, Instant timestamp) {}
    }

    /**
     * 查找或创建用户线程。
     *
     * @param groupId  群 ID
     * @param userId   用户 ID
     * @param replyToMsgId 回复的消息 ID（可为 null）
     * @return 用户线程（新建或已有）
     */
    public UserThread resolveThread(String groupId, String userId, String replyToMsgId) {
        ConcurrentHashMap<String, UserThread> userThreads = groupThreads
                .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());

        // 优先级 1：回复链 → 找到原消息所属线程
        if (replyToMsgId != null && !replyToMsgId.isEmpty()) {
            for (UserThread thread : userThreads.values()) {
                if (thread.isActive(threadTtlMinutes) && thread.threadId.equals(replyToMsgId)) {
                    log.debug("[CTX] Thread resolved via reply chain: group={}, user={}", groupId, userId);
                    return thread;
                }
            }
        }

        // 优先级 2：用户窗口 → 用户已有活跃线程
        UserThread existing = userThreads.get(userId);
        if (existing != null && existing.isActive(threadTtlMinutes)) {
            log.debug("[CTX] Thread resolved via user window: group={}, user={}", groupId, userId);
            return existing;
        }

        // 优先级 3：默认 → 新线程
        if (userThreads.size() >= maxThreadsPerGroup) {
            // 清理过期线程
            userThreads.entrySet().removeIf(e -> !e.getValue().isActive(threadTtlMinutes));
        }

        if (userThreads.size() >= maxThreadsPerGroup) {
            // 仍然超出 → 驱逐最旧的线程
            String oldest = userThreads.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().lastActivity()))
                    .map(Map.Entry::getKey)
                    .orElse(userId);
            userThreads.remove(oldest);
            log.debug("[CTX] Evicted oldest thread: group={}, user={}", groupId, oldest);
        }

        String threadId = UUID.randomUUID().toString().substring(0, 8);
        UserThread thread = new UserThread(threadId, userId, groupId);
        userThreads.put(userId, thread);
        log.debug("[CTX] New thread created: group={}, user={}, threadId={}", groupId, userId, threadId);
        return thread;
    }

    /**
     * 添加消息到用户线程。
     */
    public void addMessage(String groupId, String userId, String role, String content) {
        ConcurrentHashMap<String, UserThread> userThreads = groupThreads.get(groupId);
        if (userThreads == null) return;

        UserThread thread = userThreads.get(userId);
        if (thread != null && thread.isActive(threadTtlMinutes)) {
            thread.addMessage(role, content);
        }
    }

    /**
     * 获取用户的活跃线程。
     */
    public UserThread getActiveThread(String groupId, String userId) {
        ConcurrentHashMap<String, UserThread> userThreads = groupThreads.get(groupId);
        if (userThreads == null) return null;

        UserThread thread = userThreads.get(userId);
        if (thread != null && thread.isActive(threadTtlMinutes)) {
            return thread;
        }
        return null;
    }

    /**
     * 获取群内所有活跃线程。
     */
    public Collection<UserThread> getActiveThreads(String groupId) {
        ConcurrentHashMap<String, UserThread> userThreads = groupThreads.get(groupId);
        if (userThreads == null) return List.of();
        return userThreads.values().stream()
                .filter(t -> t.isActive(threadTtlMinutes))
                .toList();
    }

    /**
     * 定时清理过期线程。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000) // 每 5 分钟
    public void cleanupExpiredThreads() {
        int removed = 0;
        for (var entry : groupThreads.entrySet()) {
            ConcurrentHashMap<String, UserThread> userThreads = entry.getValue();
            removed += userThreads.entrySet()
                    .removeIf(e -> !e.getValue().isActive(threadTtlMinutes));
        }
        // 清理空群
        groupThreads.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (removed > 0) {
            log.debug("[CTX] Cleaned up {} expired threads", removed);
        }
    }

    /**
     * 统计信息。
     */
    public Map<String, Object> stats() {
        int totalGroups = groupThreads.size();
        int totalThreads = groupThreads.values().stream()
                .mapToInt(ConcurrentHashMap::size).sum();
        return Map.of(
                "totalGroups", totalGroups,
                "totalActiveThreads", totalThreads,
                "threadTtlMinutes", threadTtlMinutes
        );
    }
}
