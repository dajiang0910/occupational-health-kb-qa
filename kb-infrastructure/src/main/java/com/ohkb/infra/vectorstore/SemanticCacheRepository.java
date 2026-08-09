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
 * 语义缓存 Repository。
 * 双层阈值设计：>0.92 直接返回，0.85-0.92 LLM 校验。
 */
@Repository
public interface SemanticCacheRepository extends CrudRepository<Map<String, Object>, Long> {

    @Query(value = """
        SELECT id, question_text, answer_text, citations, article_ids,
               1 - (question_embedding <=> :queryEmbedding) AS similarity,
               hit_count, last_hit_at
        FROM semantic_cache
        WHERE 1 - (question_embedding <=> :queryEmbedding) >= :minSimilarity
        ORDER BY question_embedding <=> :queryEmbedding
        LIMIT 1
    """)
    Map<String, Object> findBestMatch(@Param("queryEmbedding") String queryEmbedding,
                                      @Param("minSimilarity") double minSimilarity);

    @Modifying
    @Query("UPDATE semantic_cache SET hit_count = hit_count + 1, last_hit_at = :now WHERE id = :id")
    void incrementHitCount(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM semantic_cache WHERE article_ids && :articleIds")
    void invalidateByArticleIds(@Param("articleIds") Long[] articleIds);

    @Modifying
    @Query("DELETE FROM semantic_cache WHERE last_hit_at < :before")
    void deleteExpired(@Param("before") Instant before);

    @Query("SELECT COUNT(*) FROM semantic_cache")
    long count();
}
