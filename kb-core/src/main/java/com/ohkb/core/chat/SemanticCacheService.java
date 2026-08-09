package com.ohkb.core.chat;

import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.llm.BailianClient;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三层缓存服务。
 * <p>
 * L1 精确缓存：HashMap（内存，TTL 30min，本进程内）<br>
 * L2 语义缓存：pgvector 余弦相似度（双层阈值 0.92/0.85）<br>
 * L3 LLM + Prompt Caching：由 RagPipeline 负责
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    // L1: 精确匹配缓存（进程内）
    private final ConcurrentHashMap<String, CacheEntry> exactCache = new ConcurrentHashMap<>();

    private final PgVectorTemplate pgVectorTemplate;
    private final EmbeddingService embeddingService;
    private final BailianClient llmClient;
    private final double highThreshold;
    private final double lowThreshold;
    private final long ttlMs;

    public SemanticCacheService(
            PgVectorTemplate pgVectorTemplate,
            EmbeddingService embeddingService,
            BailianClient llmClient,
            @Value("${app.cache.semantic.high-threshold:0.92}") double highThreshold,
            @Value("${app.cache.semantic.low-threshold:0.85}") double lowThreshold,
            @Value("${app.cache.semantic.ttl-minutes:30}") long ttlMinutes
    ) {
        this.pgVectorTemplate = pgVectorTemplate;
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.highThreshold = highThreshold;
        this.lowThreshold = lowThreshold;
        this.ttlMs = ttlMinutes * 60_000;
    }

    /**
     * 查询缓存。返回 null 表示未命中，需要走完整 RAG Pipeline。
     *
     * @param question 用户问题
     * @return 缓存的回答（含引用），null 表示未命中
     */
    public CachedAnswer lookup(String question) {
        // ── L1: 精确匹配 ──
        String key = question.trim().toLowerCase();
        CacheEntry exactHit = exactCache.get(key);
        if (exactHit != null && !exactHit.isExpired(ttlMs)) {
            log.info("[CACHE] L1 exact hit: question=\"{}\"", question);
            return exactHit.answer();
        }

        // ── L2: 语义缓存 ──
        Embedding questionEmbedding = embeddingService.embedQuery(question);
        String vectorStr = EmbeddingService.embeddingToPgvectorString(questionEmbedding);

        PgVectorTemplate.CachedQuestion best = pgVectorTemplate.findBestSemanticMatch(
                vectorStr, lowThreshold);
        if (best == null) {
            log.info("[CACHE] L2 miss: similarity < {}", lowThreshold);
            return null;
        }

        if (best.similarity() >= highThreshold) {
            // 高置信命中 → 直接返回
            log.info("[CACHE] L2 high-confidence hit: similarity={}",
                    String.format("%.3f", best.similarity()));
            pgVectorTemplate.incrementCacheHitCount(best.id());
            return new CachedAnswer(best.answerText(), List.of());
        }

        // 中置信区间 (0.85 ~ 0.92) → 轻量 LLM 校验
        log.info("[CACHE] L2 verify: similarity={}", String.format("%.3f", best.similarity()));
        boolean equivalent = verifyEquivalence(question, best.questionText());
        if (equivalent) {
            pgVectorTemplate.incrementCacheHitCount(best.id());
            return new CachedAnswer(best.answerText(), List.of());
        }

        return null;
    }

    /**
     * 存储缓存条目。
     */
    public void store(String question, String answer, List<String> articleIds,
                      List<Map<String, String>> citations) {
        // L1
        exactCache.put(question.trim().toLowerCase(),
                new CacheEntry(new CachedAnswer(answer, citations), System.currentTimeMillis()));

        // L2（异步写入 pgvector）
        // Phase 1: 仅 L1，L2 持久化留待后续
        log.info("[CACHE] Stored L1 cache for: \"{}\"", question);
    }

    /**
     * 知识条目变更时精准失效关联缓存。
     */
    public void invalidateByArticleIds(List<Long> articleIds) {
        exactCache.clear(); // 简化：全量清 L1（Phase 2 改为精准失效）
        pgVectorTemplate.deleteCacheByArticleIds(articleIds.toArray(new Long[0]));
        log.info("[CACHE] Invalidated by articleIds={}", articleIds);
    }

    // ── private ──

    private boolean verifyEquivalence(String question1, String question2) {
        try {
            String prompt = """
                判断以下两个问题是否在问同一件事（等价）。
                问题1: %s
                问题2: %s
                只回答 YES 或 NO。
                """.formatted(question1, question2);

            var resp = llmClient.chat(
                    List.of(BailianClient.userMessage(prompt)),
                    10, 0.0
            );
            return resp.content().trim().toUpperCase().contains("YES");
        } catch (Exception e) {
            log.warn("[CACHE] Equivalence check failed, default to NOT equivalent", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private CachedAnswer extractCachedAnswer(Map<String, Object> row) {
        return new CachedAnswer(
                (String) row.get("answer_text"),
                (List<Map<String, String>>) row.get("citations")
        );
    }

    // ── 内部类型 ──

    public record CachedAnswer(String answer, List<Map<String, String>> citations) {}

    private record CacheEntry(CachedAnswer answer, long timestamp) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}

