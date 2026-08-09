package com.ohkb.core.rag;

import com.ohkb.core.chat.SemanticCacheService;
import com.ohkb.core.knowledge.KnowledgeArticle;
import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.llm.BailianClient;
import com.ohkb.infra.vectorstore.KnowledgeArticleRepository;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG Pipeline 完整实现。
 * <p>
 * 链路：
 * <pre>
 *   语义缓存检查 → 意图+分类(合并 LLM 调用) → pgvector HNSW Top-20
 *   → [条件]Reranker → Token 预算管理 → LLM(cache_control) → 引用校验 → Confidence 计算
 * </pre>
 */
@Service
public class RagPipelineImpl implements RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineImpl.class);

    // ── 系统提示词（固定，显式缓存） ──
    private static final String SYSTEM_PROMPT = """
            你是职业健康检测SaaS系统的智能客服助手。
            你必须严格基于提供的参考文档回答用户问题。
            如果文档中没有相关信息，请诚实地告知用户"需要转接人工客服"。

            回答要求：
            1. 格式规范：使用 Markdown 格式（有序列表、表格、**加粗重点**）
            2. 引用来源：每个观点都要注明引用的文档标题
            3. 避免杜撰：不编造文档中没有的信息
            4. 简洁准确：直接回答问题，不绕弯子
            """;

    private final SemanticCacheService cacheService;
    private final PgVectorTemplate pgVectorTemplate;
    private final KnowledgeArticleRepository articleRepo;
    private final EmbeddingService embeddingService;
    private final BailianClient llmClient;
    private final IntentClassifier intentClassifier;
    private final CitationVerifier citationVerifier;
    private final ConfidenceCalculator confidenceCalc;
    private final TokenBudgetManager tokenBudget;

    // ── 配置 ──
    private final int retrievalTopK;
    private final double rerankerMinSimilarity;
    private final Map<String, Double> thresholds;

    public RagPipelineImpl(
            SemanticCacheService cacheService,
            PgVectorTemplate pgVectorTemplate,
            KnowledgeArticleRepository articleRepo,
            EmbeddingService embeddingService,
            BailianClient llmClient,
            @Value("${app.rag.retriever.top-k:20}") int retrievalTopK,
            @Value("${app.rag.retriever.reranker-min-similarity:0.8}") double rerankerMinSimilarity,
            @Value("${app.rag.confidence.thresholds.factual:0.80}") double factualThreshold,
            @Value("${app.rag.confidence.thresholds.howto:0.65}") double howtoThreshold,
            @Value("${app.rag.confidence.thresholds.troubleshoot:0.50}") double troubleshootThreshold
    ) {
        this.cacheService = cacheService;
        this.pgVectorTemplate = pgVectorTemplate;
        this.articleRepo = articleRepo;
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.intentClassifier = new IntentClassifier(llmClient);
        this.citationVerifier = new CitationVerifier(llmClient);
        this.confidenceCalc = new ConfidenceCalculator(
                llmClient,
                factualThreshold, howtoThreshold, troubleshootThreshold,
                0.4, 0.6); // LLM weight 0.4, retrieval weight 0.6
        this.tokenBudget = new TokenBudgetManager(500, 2000, 2000, 1500);
        this.retrievalTopK = retrievalTopK;
        this.rerankerMinSimilarity = rerankerMinSimilarity;
        this.thresholds = Map.of(
                "factual", factualThreshold,
                "howto", howtoThreshold,
                "troubleshoot", troubleshootThreshold
        );
    }

    @Override
    public RagResult answer(RagRequest request) {
        String traceId = MDC.get("traceId");
        long pipelineStart = System.currentTimeMillis();

        log.info("[RAG] question=\"{}\", historySize={}, filters={}",
                request.question(), request.history().size(), request.filters());

        try {
            // ── Step 1: 语义缓存检查 ──
            var cached = cacheService.lookup(request.question());
            if (cached != null) {
                return new RagResult(cached.answer(), cached.citations(),
                        1.0, List.of(), List.of(), false, false);
            }

            // ── Step 2: 意图识别 + 问题分类（合并为一次 LLM 调用）──
            IntentClassifier.Result intent = intentClassifier.classify(request.question());
            log.info("[RAG] intent={}, category={}", intent.intent(), intent.category());

            // 非问题类消息 → 简短回复
            if (!"question".equals(intent.intent())) {
                return new RagResult("您好！有什么可以帮助您的吗？",
                        List.of(), 1.0, List.of(), List.of(), false, false);
            }

            // ── Step 3: pgvector 向量检索 ──
            Embedding questionEmbedding = embeddingService.embedQuery(request.question());
            String vectorStr = EmbeddingService.embeddingToPgvectorString(questionEmbedding);

            List<KnowledgeArticle> candidates = pgVectorTemplate.findNearest(vectorStr, retrievalTopK);
            log.info("[RAG] Retrieved {} candidates", candidates.size());

            if (candidates.isEmpty()) {
                return RagResult.fallback("抱歉，未找到相关信息，系统将为您转接人工客服。",
                        List.of());
            }

            // ── Step 4: [条件] Reranker ──
            List<KnowledgeArticle> topArticles = candidates;
            double top1Similarity = estimateCosineSimilarity(questionEmbedding, candidates.get(0));

            if (top1Similarity < rerankerMinSimilarity && candidates.size() > 5) {
                // 启用 Reranker（Cross-encoder 重排序）
                topArticles = rerank(candidates, request.question(), 5);
                log.info("[RAG] Reranker applied: top1_sim={} < {} → Top-{}",
                        String.format("%.3f", top1Similarity), rerankerMinSimilarity,
                        topArticles.size());
            } else {
                topArticles = candidates.subList(0, Math.min(5, candidates.size()));
            }

            // ── Step 5: Token 预算管理 ──
            String docsContext = buildDocsContext(topArticles, tokenBudget.docBudget());

            // ── Step 6: LLM 生成（显式 cache_control）──
            List<Map<String, Object>> messages = new ArrayList<>();

            // System Prompt 带 cache_control（Layer 1：固定，缓存命中率 ≈100%）
            messages.add(BailianClient.systemWithCache(SYSTEM_PROMPT));

            // 历史消息（已压缩）
            String historyText = tokenBudget.compressHistory(request.history());
            if (!historyText.isEmpty()) {
                messages.add(BailianClient.userMessage("对话历史：\n" + historyText));
            }

            // 知识库文档带 cache_control（Layer 2：热文档，高频命中）
            // 当前问题（Layer 3：动态，不缓存）
            messages.add(BailianClient.userWithCache(docsContext, request.question()));

            BailianClient.ChatResponse llmResp = llmClient.chat(messages, 1024, 0.7);

            log.info("[RAG] LLM: total={}, cached={} ({}%), creation={}, latency={}ms",
                    llmResp.totalTokens(), llmResp.cachedTokens(),
                    String.format("%.1f", llmResp.cacheHitRate() * 100),
                    llmResp.cacheCreationTokens(), llmResp.latencyMs());

            // ── Step 7: 引用校验 ──
            List<RagResult.Citation> citations = buildCitations(topArticles);
            String verifiedAnswer = citationVerifier.verify(llmResp.content(), citations);

            // ── Step 8: Confidence 计算 ──
            double confidence = confidenceCalc.compute(intent.category(), verifiedAnswer,
                    top1Similarity, request.question());

            // 判断是否需要转人工
            double threshold = thresholds.getOrDefault(intent.category(), 0.65);
            boolean fallback = confidence < threshold;

            // ── Step 9: 存储语义缓存 ──
            List<String> articleIds = topArticles.stream()
                    .map(a -> a.id().toString())
                    .toList();
            cacheService.store(request.question(), verifiedAnswer, articleIds,
                    citations.stream()
                            .map(c -> Map.of("articleId", c.articleId(), "title", (String) c.title(), "snippet", (String) c.snippet()))
                            .collect(Collectors.toList()));

            // 更新命中计数
            articleRepo.incrementHitCount(topArticles.stream().map(KnowledgeArticle::id).toList());

            long pipelineLatency = System.currentTimeMillis() - pipelineStart;
            log.info("[RAG] Complete: confidence={}, fallback={}, latency={}ms",
                    String.format("%.2f", confidence), fallback, pipelineLatency);

            return new RagResult(
                    verifiedAnswer, citations, confidence,
                    generateRelatedQuestions(request.question(), intent.category()),
                    articleIds, fallback, false
            );

        } catch (Exception e) {
            log.error("[RAG] Pipeline error: {}", e.getMessage(), e);
            // 触发降级 → 返回纯检索结果
            try {
                Embedding questionEmbedding = embeddingService.embedQuery(request.question());
                String vectorStr = EmbeddingService.embeddingToPgvectorString(questionEmbedding);
                List<KnowledgeArticle> docs = pgVectorTemplate.findNearest(vectorStr, 5);
                List<RagResult.Citation> citations = buildCitations(docs);
                return RagResult.degraded(citations);
            } catch (Exception nested) {
                log.error("[RAG] Degrade also failed", nested);
                return RagResult.fallback("系统正在维护中，请稍后再试。紧急问题请联系人工客服。",
                        List.of());
            }
        }
    }

    // ── private helpers ──

    private String buildDocsContext(List<KnowledgeArticle> articles, int maxTokens) {
        StringBuilder sb = new StringBuilder("参考文档：\n\n");
        int tokens = 0;
        for (KnowledgeArticle a : articles) {
            String entry = "---\n标题: " + a.title() + "\n内容: " + a.content() + "\n\n";
            int entryTokens = entry.length() / 2; // 粗略：1 token ≈ 2 字符
            if (tokens + entryTokens > maxTokens) break;
            sb.append(entry);
            tokens += entryTokens;
        }
        return sb.toString();
    }

    private double estimateCosineSimilarity(Embedding q, KnowledgeArticle article) {
        // Phase 1：简化实现，使用 pgvector <=> 距离（1 - distance = similarity）
        // Phase 2：实际计算余弦相似度
        return 0.85; // 占位（实际从 pgvector 查询结果中获取）
    }

    private List<KnowledgeArticle> rerank(List<KnowledgeArticle> candidates,
                                          String question, int topN) {
        // Phase 1：简单截断 Top-N（Phase 2 集成 Cross-encoder）
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }

    private List<RagResult.Citation> buildCitations(List<KnowledgeArticle> articles) {
        return articles.stream()
                .map(a -> new RagResult.Citation(
                        a.id() != null ? a.id().toString() : "",
                        a.title(), a.content().substring(0, Math.min(100, a.content().length())),
                        true))
                .collect(Collectors.toList());
    }

    private List<String> generateRelatedQuestions(String question, String category) {
        // Phase 1：按分类推荐固定问题（Phase 2：LLM 动态生成）
        return switch (category) {
            case "factual" -> List.of("这个功能在哪个菜单？", "相关参数的含义是什么？");
            case "howto" -> List.of("完整的操作流程是什么？", "操作失败如何处理？");
            case "troubleshoot" -> List.of("常见的错误原因是什么？", "如何避免这个问题？");
            default -> List.of("了解更多操作指引", "查看常见问题");
        };
    }
}
