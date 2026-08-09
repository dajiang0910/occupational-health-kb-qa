package com.ohkb.infra.vectorstore;

import com.ohkb.core.knowledge.KnowledgeArticle;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 知识条目 Repository。
 * pgvector 查询使用原生 SQL（Spring Data JDBC 不直接支持向量操作）。
 */
@Repository
public interface KnowledgeArticleRepository extends CrudRepository<KnowledgeArticle, Long> {

    List<KnowledgeArticle> findByCategory(String category);

    List<KnowledgeArticle> findBySourceDocId(Long sourceDocId);

    @Query("SELECT * FROM knowledge_articles WHERE is_deprecated = false AND chunk_status = 'ok'")
    List<KnowledgeArticle> findActiveArticles();

    @Query("""
        SELECT * FROM knowledge_articles
        WHERE is_deprecated = false AND chunk_status = 'ok'
        AND embedding IS NOT NULL
        ORDER BY embedding <=> :queryEmbedding
        LIMIT :limit
    """)
    List<KnowledgeArticle> findNearest(@Param("queryEmbedding") String queryEmbedding,
                                       @Param("limit") int limit);

    @Modifying
    @Query("UPDATE knowledge_articles SET hit_count = hit_count + 1 WHERE id IN (:ids)")
    void incrementHitCount(@Param("ids") List<Long> ids);

    @Modifying
    @Query("UPDATE knowledge_articles SET is_deprecated = true WHERE id IN (:ids)")
    void markDeprecated(@Param("ids") List<Long> ids);

    @Modifying
    @Query("UPDATE knowledge_articles SET helpful_count = helpful_count + 1 WHERE id = :id")
    void incrementHelpful(@Param("id") Long id);

    @Modifying
    @Query("UPDATE knowledge_articles SET unhelpful_count = unhelpful_count + 1 WHERE id = :id")
    void incrementUnhelpful(@Param("id") Long id);

    @Query("SELECT * FROM knowledge_articles WHERE category = :category AND is_deprecated = false")
    List<KnowledgeArticle> findByCategoryActive(@Param("category") String category);

    @Query("SELECT DISTINCT category FROM knowledge_articles WHERE is_deprecated = false")
    List<String> findActiveCategories();

    @Query("SELECT COUNT(*) FROM knowledge_articles WHERE is_deprecated = false AND chunk_status = 'ok'")
    long countActiveArticles();
}
