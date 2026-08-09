package com.ohkb.core.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工单服务。
 * <p>
 * 状态机：PENDING → CLAIMED → RESOLVED
 * 知识化流程异步，不阻塞工单关闭。
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    // Phase 1：内存存储（Phase 2：Repository 持久化）
    private final ConcurrentHashMap<Long, SupportTicket> tickets = new ConcurrentHashMap<>();
    private long idCounter = 0;

    /**
     * 创建工单。
     */
    public SupportTicket createTicket(Long conversationId, Long sourceMessageId,
                                       String channel, String aiAnswerSnapshot,
                                       List<Long> retrievedArticles) {
        SupportTicket ticket = SupportTicket.create(
                conversationId, sourceMessageId, channel, aiAnswerSnapshot, retrievedArticles);
        long id = ++idCounter;
        tickets.put(id, ticket);
        log.info("[TICKET] Created: id={}, channel={}", id, channel);

        // TODO: 发布 ApplicationEvent → 浏览器通知 + 企微通知

        return ticket;
    }

    /**
     * 认领工单。
     */
    public SupportTicket claimTicket(Long ticketId, String assignedTo) {
        SupportTicket existing = tickets.get(ticketId);
        if (existing == null) throw new IllegalArgumentException("Ticket not found: " + ticketId);

        SupportTicket claimed = existing.claim(assignedTo);
        tickets.put(ticketId, claimed);
        log.info("[TICKET] Claimed: id={}, assignedTo={}", ticketId, assignedTo);
        return claimed;
    }

    /**
     * 解决工单。
     */
    public SupportTicket resolveTicket(Long ticketId, String resolutionNote,
                                        Long knowledgeArticleCreated) {
        SupportTicket existing = tickets.get(ticketId);
        if (existing == null) throw new IllegalArgumentException("Ticket not found: " + ticketId);

        SupportTicket resolved = existing.resolve(resolutionNote, knowledgeArticleCreated);
        tickets.put(ticketId, resolved);
        log.info("[TICKET] Resolved: id={}", ticketId);
        return resolved;
    }

    /**
     * 获取待处理工单列表。
     */
    public List<SupportTicket> getPendingTickets() {
        return tickets.values().stream()
                .filter(t -> SupportTicket.Status.PENDING.name().equals(t.status()))
                .toList();
    }

    /**
     * 获取工单详情。
     */
    public SupportTicket getTicket(Long ticketId) {
        SupportTicket ticket = tickets.get(ticketId);
        if (ticket == null) throw new IllegalArgumentException("Ticket not found: " + ticketId);
        return ticket;
    }
}
