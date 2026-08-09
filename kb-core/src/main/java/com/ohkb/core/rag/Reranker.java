package com.ohkb.core.rag;

import com.ohkb.core.knowledge.KnowledgeArticle;
import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.llm.BailianClient;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索结果重排序。
 * <p>
 * 当检索 Top-1 余弦相似度 < 阈值时启用重排序：
 * <ol>
 *   <li>Embedding 相似度重排序（快速，无额外 API 调用）</li>
 *   <li>LLM Cross-encoder 重排序（精确，需要额外 API 调用——Phase 2）</li>
 * </ol>
 */
@Component
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private final EmbeddingService embeddingService;
    private final BailianClient llmClient;
    private final double rerankerMinSimilarity;
    private final int rerankerTopN;

    public Reranker(
            EmbeddingService embeddingService,
            BailianClient llmClient,
            @Value("${app.rag.retriever.reranker-min-similarity:0.8}") double rerankerMinSimilarity,
            @Value("${app.rag.retriever.reranker-top-n:5}") int rerankerTopN
    ) {
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.rerankerMinSimilarity = rerankerMinSimilarity;
        this.rerankerTopN = rerankerTopN;
    }

    /**
     * 重排序检索结果。
     *
     * @param candidates 原始检索结果（按 pgvector 相似度排序）
     * @param question   用户问题
     * @return 重排序后的 Top-N 结果
     */
    public List<KnowledgeArticle> rerank(List<KnowledgeArticle> candidates, String question) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // ── 方式 1：Embedding 相似度重排序 ──
        List<ScoredArticle> scored = rerankByEmbedding(candidates, question);

        // ── 方式 2：LLM Cross-encoder 重排序（Phase 2）──
        // 当 Top-1 embedding 相似度仍不足时，启用 LLM pairwise 比较

        double topScore = scored.isEmpty() ? 0.0 : scored.get(0).score;
        if (topScore < rerankerMinSimilarity && scored.size() >= 3) {
            log.info("[RERANK] Top-1 embedding score {:.3f} < {}, applying LLM cross-encoder",
                    topScore, rerankerMinSimilarity);
            scored = rerankByCrossEncoder(scored, question);
        }

        return scored.stream()
                .limit(rerankerTopN)
                .map(s -> s.article)
                .collect(Collectors.toList());
    }

    /**
     * 基于 Embedding 相似度重排序。
     * <p>
     * 对每个候选文档计算其与问题的余弦相似度，按相似度降序排列。
     */
    private List<ScoredArticle> rerankByEmbedding(List<KnowledgeArticle> candidates, String question) {
        Embedding questionEmbedding = embeddingService.embedQuery(question);

        return candidates.stream()
                .map(article -> {
                    double score = article.similarity() != null
                            ? article.similarity()
                            : 0.0;
                    return new ScoredArticle(article, score);
                })
                .sorted(Comparator.comparingDouble(ScoredArticle::score).reversed())
                .collect(Collectors.toList());
    }

    /**
     * LLM Cross-encoder 重排序（Pointwise 方式）。
     * <p>
     * 对每个候选文档，让 LLM 判断其与问题的相关度（1-5 分），
     * 取 Top-N 作为最终结果。Phase 2 升级为 Pairwise 比较以提升精度。
     */
    private List<ScoredArticle> rerankByCrossEncoder(List<ScoredArticle> candidates, String question) {
        List<ScoredArticle> rescored = new ArrayList<>();

        // 对前 10 个候选做 Cross-encoder 评分
        List<ScoredArticle> topCandidates = candidates.stream()
                .limit(10)
                .collect(Collectors.toList());

        for (ScoredArticle candidate : topCandidates) {
            try {
                String prompt = String.format("""
                        判断以下文档内容与用户问题的相关度，给出 1-5 的评分。
                        只输出数字。

                        用户问题：%s

                        文档内容：%s
                        """, question, candidate.article.content().substring(0,
                        Math.min(400, candidate.article.content().length())));

                BailianClient.ChatResponse resp = llmClient.chat(
                        List.of(BailianClient.userMessage(prompt)),
                        5, 0.0
                );

                double score = parseRelevanceScore(resp.content());
                rescored.add(new ScoredArticle(candidate.article,
                        score / 5.0)); // 归一化到 0-1

            } catch (Exception e) {
                log.warn("[RERANK] Cross-encoder failed for article {}: {}",
                        candidate.article.id(), e.getMessage());
                // Fallback：保留原相似度
                rescored.add(candidate);
            }
        }

        return rescored.stream()
                .sorted(Comparator.comparingDouble(ScoredArticle::score).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 从 LLM 输出中解析相关度评分（1-5）。
     */
    private double parseRelevanceScore(String content) {
        if (content == null) return 2.0;
        String trimmed = content.trim();
        try {
            double score = Double.parseDouble(trimmed);
            return Math.max(1.0, Math.min(5.0, score));
        } catch (NumberFormatException e) {
            // 尝试提取第一个数字
            String digits = trimmed.replaceAll("[^0-9.]", "");
            if (!digits.isEmpty()) {
                try {
                    double score = Double.parseDouble(digits);
                    return Math.max(1.0, Math.min(5.0, score));
                } catch (NumberFormatException ignored) {}
            }
        }
        return 2.0; // 默认中等相关
    }

    // ── 内部类型 ──

    private record ScoredArticle(KnowledgeArticle article, double score) {}
}
