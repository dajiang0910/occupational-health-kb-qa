package com.ohkb.core.chat;

import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.llm.BailianClient;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
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
            return new CachedAnswer(best.answerText(), parseCitations(best.citations()));
        }

        // 中置信区间 (0.85 ~ 0.92) → 轻量 LLM 校验
        log.info("[CACHE] L2 verify: similarity={}", String.format("%.3f", best.similarity()));
        boolean equivalent = verifyEquivalence(question, best.questionText());
        if (equivalent) {
            pgVectorTemplate.incrementCacheHitCount(best.id());
            return new CachedAnswer(best.answerText(), parseCitations(best.citations()));
        }

        return null;
    }

    /**
     * 存储缓存条目。
     */
    public void store(String question, String answer, List<String> articleIds,
                      List<Map<String, String>> citations) {
        // L1：内存精确缓存（同步）
        exactCache.put(question.trim().toLowerCase(),
                new CacheEntry(new CachedAnswer(answer, citations), System.currentTimeMillis()));

        // L2：pgvector 语义缓存（异步，失败不影响 L1）
        try {
            Embedding questionEmbedding = embeddingService.embedQuery(question);
            String vectorStr = EmbeddingService.embeddingToPgvectorString(questionEmbedding);
            String citationsJson = toJson(citations);
            Long[] articleIdArray = articleIds.stream()
                    .map(Long::parseLong)
                    .toArray(Long[]::new);

            pgVectorTemplate.insertSemanticCache(question, answer, citationsJson,
                    vectorStr, articleIdArray);
            log.info("[CACHE] Stored L1+L2 cache for: \"{}\"", question);
        } catch (Exception e) {
            log.warn("[CACHE] L2 persistence failed (L1 still valid): {}", e.getMessage());
        }
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

    // ── JSON helpers (simple manual impl to avoid dependency on Jackson in kb-core) ──

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseCitations(String citationsJson) {
        if (citationsJson == null || citationsJson.isBlank() || "[]".equals(citationsJson)) {
            return List.of();
        }
        // Simple parser for JSON array of {key: value} objects
        // Format: [{"articleId":"123","title":"...","snippet":"..."},...]
        List<Map<String, String>> result = new ArrayList<>();
        try {
            String content = citationsJson.trim();
            if (content.startsWith("[")) content = content.substring(1);
            if (content.endsWith("]")) content = content.substring(0, content.length() - 1);

            int depth = 0, start = -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{' && depth == 0) start = i;
                if (c == '{') depth++;
                if (c == '}') depth--;
                if (c == '}' && depth == 0 && start >= 0) {
                    String obj = content.substring(start, i + 1);
                    Map<String, String> map = new HashMap<>();
                    // Parse key-value pairs inside {...}
                    String inner = obj.substring(1, obj.length() - 1);
                    int pos = 0;
                    while (pos < inner.length()) {
                        int keyStart = inner.indexOf('"', pos);
                        if (keyStart < 0) break;
                        int keyEnd = inner.indexOf('"', keyStart + 1);
                        int colon = inner.indexOf(':', keyEnd);
                        int valStart = inner.indexOf('"', colon);
                        int valEnd = inner.indexOf('"', valStart + 1);
                        if (keyStart >= 0 && keyEnd > keyStart && valStart > colon && valEnd > valStart) {
                            String key = inner.substring(keyStart + 1, keyEnd);
                            String val = inner.substring(valStart + 1, valEnd);
                            map.put(key, val);
                            pos = valEnd + 1;
                        } else {
                            break;
                        }
                    }
                    if (!map.isEmpty()) result.add(map);
                    start = -1;
                }
            }
        } catch (Exception e) {
            log.warn("[CACHE] Failed to parse citations JSON", e);
        }
        return result;
    }

    private String toJson(List<Map<String, String>> citations) {
        if (citations == null || citations.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < citations.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            Map<String, String> map = citations.get(i);
            int j = 0;
            for (var entry : map.entrySet()) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey()))
                        .append("\":\"").append(escapeJson(entry.getValue())).append("\"");
                j++;
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── 内部类型 ──

    public record CachedAnswer(String answer, List<Map<String, String>> citations) {}

    private record CacheEntry(CachedAnswer answer, long timestamp) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}

