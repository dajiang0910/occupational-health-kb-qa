package com.ohkb.infra.vectorstore;

import com.ohkb.core.knowledge.KnowledgeArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * pgvector 原生 SQL 操作模板。
 * <p>
 * Spring Data JDBC 不直接支持 VECTOR 类型，因此向量相关的读写走此模板。
 */
@Repository
public class PgVectorTemplate {

    private static final Logger log = LoggerFactory.getLogger(PgVectorTemplate.class);

    private final JdbcTemplate jdbc;

    public PgVectorTemplate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入知识条目（含向量）。
     */
    public void insertArticle(KnowledgeArticle article, String vectorStr) {
        jdbc.update("""
            INSERT INTO knowledge_articles
                (title, content, content_json, category, tags, source_type,
                 source_doc_id, chunk_index, chunk_status, embedding, embedding_model,
                 effective_from, effective_until, is_deprecated, hit_count,
                 helpful_count, unhelpful_count, created_at, updated_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?,
                    ?, ?, ?, ?::vector, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?)
            """,
            article.title(), article.content(), article.contentJson(),
            article.category(), article.tags().toArray(new String[0]), article.sourceType(),
            article.sourceDocId(), article.chunkIndex(), article.chunkStatus(),
            vectorStr, article.embeddingModel(),
            article.effectiveFrom(), article.effectiveUntil(), article.isDeprecated(),
            article.hitCount(), article.helpfulCount(), article.unhelpfulCount(),
            article.createdAt(), article.updatedAt()
        );
    }

    /**
     * 按向量余弦相似度检索最近的知识条目。
     */
    public List<KnowledgeArticle> findNearest(String queryVector, int limit) {
        return jdbc.query("""
            SELECT id, title, content, content_json, category, tags, source_type,
                   source_doc_id, chunk_index, chunk_status, embedding_model,
                   effective_from, effective_until, is_deprecated,
                   hit_count, helpful_count, unhelpful_count,
                   created_at, updated_at,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM knowledge_articles
            WHERE is_deprecated = false AND chunk_status = 'ok' AND embedding IS NOT NULL
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """,
            new KnowledgeArticleRowMapper(),
            queryVector, queryVector, limit
        );
    }

    /**
     * 语义缓存：查找最相似问题。
     */
    public CachedQuestion findBestSemanticMatch(String queryVector, double minSimilarity) {
        List<CachedQuestion> results = jdbc.query("""
            SELECT id, question_text, answer_text, citations, article_ids,
                   1 - (question_embedding <=> ?::vector) AS similarity,
                   hit_count, last_hit_at
            FROM semantic_cache
            WHERE 1 - (question_embedding <=> ?::vector) >= ?
            ORDER BY question_embedding <=> ?::vector
            LIMIT 1
            """,
            (rs, rowNum) -> new CachedQuestion(
                    rs.getLong("id"),
                    rs.getString("question_text"),
                    rs.getString("answer_text"),
                    rs.getString("citations"),
                    (Long[]) rs.getArray("article_ids").getArray(),
                    rs.getDouble("similarity")
            ),
            queryVector, queryVector, minSimilarity, queryVector
        );
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 插入语义缓存。
     */
    public void insertSemanticCache(String questionText, String answerText,
                                     String citations, String vectorStr,
                                     Long[] articleIds) {
        jdbc.update("""
            INSERT INTO semantic_cache
                (question_embedding, question_text, answer_text, citations, article_ids, hit_count, last_hit_at)
            VALUES (?::vector, ?, ?, ?::jsonb, ?, 1, NOW())
            """,
            vectorStr, questionText, answerText, citations, articleIds
        );
    }

    /**
     * 语义缓存命中计数更新。
     */
    public void incrementCacheHitCount(long cacheId) {
        jdbc.update("UPDATE semantic_cache SET hit_count = hit_count + 1, last_hit_at = NOW() WHERE id = ?",
                cacheId);
    }

    /**
     * 按关联文章 ID 删除语义缓存。
     */
    public void deleteCacheByArticleIds(Long[] articleIds) {
        jdbc.update("DELETE FROM semantic_cache WHERE article_ids && ?", (Object) articleIds);
    }

    /**
     * 过期语义缓存清理。
     */
    public int deleteExpiredCache(Instant before) {
        return jdbc.update("DELETE FROM semantic_cache WHERE last_hit_at < ?", before);
    }

    /**
     * 语义缓存数量。
     */
    public long countCache() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM semantic_cache", Long.class);
        return count != null ? count : 0;
    }

    // ── RowMapper ──

    private static class KnowledgeArticleRowMapper implements RowMapper<KnowledgeArticle> {
        @Override
        public KnowledgeArticle mapRow(ResultSet rs, int rowNum) throws SQLException {
            String[] tagArray = (String[]) rs.getArray("tags").getArray();
            return new KnowledgeArticle(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("content_json"),
                    rs.getString("category"),
                    Arrays.asList(tagArray),
                    rs.getString("source_type"),
                    rs.getObject("source_doc_id", Long.class),
                    rs.getObject("chunk_index", Integer.class),
                    rs.getString("chunk_status"),
                    rs.getString("embedding_model"),
                    rs.getTimestamp("effective_from").toInstant(),
                    rs.getTimestamp("effective_until") != null
                            ? rs.getTimestamp("effective_until").toInstant() : null,
                    rs.getBoolean("is_deprecated"),
                    rs.getInt("hit_count"),
                    rs.getInt("helpful_count"),
                    rs.getInt("unhelpful_count"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }
    }

    public record CachedQuestion(
            long id,
            String questionText,
            String answerText,
            String citations,
            Long[] articleIds,
            double similarity
    ) {}
}
