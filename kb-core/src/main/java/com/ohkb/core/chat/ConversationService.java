package com.ohkb.core.chat;

import com.ohkb.infra.vectorstore.ConversationEntity;
import com.ohkb.infra.vectorstore.ConversationRepository;
import com.ohkb.infra.vectorstore.MessageEntity;
import com.ohkb.infra.vectorstore.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 对话服务——管理 conversations 和 messages 的持久化。
 * <p>
 * 用于：
 * <ul>
 *   <li>Web 端：用户历史对话列表和详情</li>
 *   <li>企微端：群对话历史查看</li>
 *   <li>反馈分类：从对话中获取原始问题和 AI 回答</li>
 * </ul>
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;

    public ConversationService(ConversationRepository conversationRepo,
                                MessageRepository messageRepo) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
    }

    /**
     * 创建对话。
     */
    @Transactional
    public ConversationEntity createConversation(String channel, String externalId, String userId) {
        // external_id 字段名在 entity 中是 externalId
        var entity = conversationRepo.save(new ConversationEntity(
                null, channel, externalId, userId, "active",
                java.time.Instant.now(), java.time.Instant.now()));
        log.debug("[CONV] Created: id={}, channel={}, userId={}", entity.id(), channel, userId);
        return entity;
    }

    /**
     * 获取或创建活跃对话。
     */
    @Transactional
    public ConversationEntity getOrCreateConversation(String channel, String externalId, String userId) {
        List<ConversationEntity> active = conversationRepo.findActiveByChannel(channel, externalId);
        if (!active.isEmpty()) {
            return active.get(0);
        }
        return createConversation(channel, externalId, userId);
    }

    /**
     * 添加消息到对话。
     */
    @Transactional
    public MessageEntity addMessage(Long conversationId, String role, String content,
                                     String citations, Double confidence) {
        var msg = messageRepo.save(new MessageEntity(
                null, conversationId, role, content, citations, confidence,
                null, null, null, java.time.Instant.now()));
        // 更新对话时间戳
        conversationRepo.touch(conversationId);
        return msg;
    }

    /**
     * 获取对话消息（最近 N 条）。
     */
    public List<MessageEntity> getRecentMessages(Long conversationId, int limit) {
        return messageRepo.findByConversationId(conversationId, limit);
    }

    /**
     * 获取对话全部消息。
     */
    public List<MessageEntity> getAllMessages(Long conversationId) {
        return messageRepo.findAllByConversationId(conversationId);
    }

    /**
     * 更新反馈。
     */
    @Transactional
    public void updateFeedback(Long messageId, String feedback, String category, String note) {
        messageRepo.updateFeedback(messageId, feedback, category, note);
        log.info("[CONV] Feedback updated: messageId={}, feedback={}, category={}", messageId, feedback, category);
    }

    /**
     * 关闭对话。
     */
    @Transactional
    public void closeConversation(Long conversationId) {
        conversationRepo.updateStatus(conversationId, "closed");
        log.info("[CONV] Closed: id={}", conversationId);
    }

    /**
     * 获取用户历史对话列表。
     */
    public List<ConversationEntity> getUserConversations(String userId, int limit) {
        return conversationRepo.findByUserId(userId, limit);
    }

    /**
     * 获取对话详情。
     */
    public Optional<ConversationEntity> getConversation(Long id) {
        return conversationRepo.findById(id);
    }

    /**
     * 获取对话消息数量。
     */
    public int getMessageCount(Long conversationId) {
        return messageRepo.countByConversationId(conversationId);
    }
}
