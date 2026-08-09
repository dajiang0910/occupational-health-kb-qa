package com.ohkb.core.knowledge;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;

/**
 * 知识条目领域实体。
 */
@Table("knowledge_articles")
public record KnowledgeArticle(
        @Id Long id,
        String title,
        String content,
        String contentJson,
        String category,
        List<String> tags,
        String sourceType,
        Long sourceDocId,
        Integer chunkIndex,
        String chunkStatus,
        String embeddingModel,
        Instant effectiveFrom,
        Instant effectiveUntil,
        Boolean isDeprecated,
        Integer hitCount,
        Integer helpfulCount,
        Integer unhelpfulCount,
        Instant createdAt,
        Instant updatedAt,
        Double similarity  // 向量检索时的余弦相似度（非持久化字段，仅查询时填充）
) {
    public static KnowledgeArticle create(String title, String content, String category,
                                          String sourceType, List<String> tags) {
        return new KnowledgeArticle(
                null, title, content, null, category, tags != null ? tags : List.of(),
                sourceType, null, null, "ok", "text-embedding-v3",
                Instant.now(), null, false, 0, 0, 0, Instant.now(), Instant.now(), null
        );
    }

    public KnowledgeArticle withDeprecated(boolean deprecated) {
        return new KnowledgeArticle(id, title, content, contentJson, category, tags,
                sourceType, sourceDocId, chunkIndex, chunkStatus, embeddingModel,
                effectiveFrom, effectiveUntil, deprecated, hitCount, helpfulCount,
                unhelpfulCount, createdAt, Instant.now(), similarity);
    }
}
