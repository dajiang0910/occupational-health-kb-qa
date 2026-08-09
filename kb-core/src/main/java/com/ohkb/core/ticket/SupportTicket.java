package com.ohkb.core.ticket;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;

/**
 * 工单领域实体。
 * 状态机：PENDING → CLAIMED → RESOLVED
 */
@Table("support_tickets")
public record SupportTicket(
        @Id Long id,
        Long conversationId,
        Long sourceMessageId,
        String channel,
        String status,
        String priority,
        String assignedTo,
        String aiAnswerSnapshot,
        List<Long> retrievedArticles,
        String resolutionNote,
        Long knowledgeArticleCreated,
        Instant claimedAt,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status { PENDING, CLAIMED, RESOLVED }

    public static SupportTicket create(Long conversationId, Long sourceMessageId,
                                        String channel, String aiAnswerSnapshot,
                                        List<Long> retrievedArticles) {
        return new SupportTicket(null, conversationId, sourceMessageId, channel,
                Status.PENDING.name(), "normal", null, aiAnswerSnapshot,
                retrievedArticles, null, null, null, null, Instant.now(), Instant.now());
    }

    public SupportTicket claim(String assignedTo) {
        if (!Status.PENDING.name().equals(status)) {
            throw new IllegalStateException("Only PENDING tickets can be claimed, current: " + status);
        }
        return new SupportTicket(id, conversationId, sourceMessageId, channel,
                Status.CLAIMED.name(), priority, assignedTo, aiAnswerSnapshot,
                retrievedArticles, resolutionNote, knowledgeArticleCreated,
                Instant.now(), resolvedAt, createdAt, Instant.now());
    }

    public SupportTicket resolve(String resolutionNote, Long knowledgeArticleCreated) {
        if (!Status.CLAIMED.name().equals(status)) {
            throw new IllegalStateException("Only CLAIMED tickets can be resolved, current: " + status);
        }
        return new SupportTicket(id, conversationId, sourceMessageId, channel,
                Status.RESOLVED.name(), priority, assignedTo, aiAnswerSnapshot,
                retrievedArticles, resolutionNote, knowledgeArticleCreated,
                claimedAt, Instant.now(), createdAt, Instant.now());
    }
}
