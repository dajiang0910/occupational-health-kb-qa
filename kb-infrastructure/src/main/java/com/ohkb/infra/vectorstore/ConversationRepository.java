package com.ohkb.infra.vectorstore;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 对话 Repository——conversations 表操作。
 */
@Repository
public interface ConversationRepository extends CrudRepository<ConversationEntity, Long> {

    @Query("SELECT * FROM conversations WHERE user_id = :userId ORDER BY updated_at DESC LIMIT :limit")
    List<ConversationEntity> findByUserId(@Param("userId") String userId, @Param("limit") int limit);

    @Query("SELECT * FROM conversations WHERE channel = :channel AND external_id = :externalId AND status = 'active'")
    List<ConversationEntity> findActiveByChannel(@Param("channel") String channel,
                                                  @Param("externalId") String externalId);

    @Modifying
    @Query("UPDATE conversations SET status = :status, updated_at = NOW() WHERE id = :id")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query("UPDATE conversations SET updated_at = NOW() WHERE id = :id")
    void touch(@Param("id") Long id);
}

/**
 * conversations 表行映射。
 */
record ConversationEntity(
        @org.springframework.data.annotation.Id Long id,
        String channel,
        String externalId,
        String userId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
