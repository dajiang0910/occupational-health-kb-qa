package com.ohkb.infra.vectorstore;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 消息 Repository——messages 表操作。
 */
@Repository
public interface MessageRepository extends CrudRepository<MessageEntity, Long> {

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY created_at ASC LIMIT :limit")
    List<MessageEntity> findByConversationId(@Param("convId") Long convId, @Param("limit") int limit);

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY created_at ASC")
    List<MessageEntity> findAllByConversationId(@Param("convId") Long convId);

    @Modifying
    @Query("""
        UPDATE messages SET feedback = :feedback, feedback_category = :category,
        feedback_note = :note WHERE id = :id
    """)
    void updateFeedback(@Param("id") Long id, @Param("feedback") String feedback,
                        @Param("category") String category, @Param("note") String note);

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :convId")
    int countByConversationId(@Param("convId") Long convId);
}

/**
 * messages 表行映射。
 */
record MessageEntity(
        @org.springframework.data.annotation.Id Long id,
        Long conversationId,
        String role,
        String content,
        String citations,
        Double confidence,
        String feedback,
        String feedbackCategory,
        String feedbackNote,
        Instant createdAt
) {}
