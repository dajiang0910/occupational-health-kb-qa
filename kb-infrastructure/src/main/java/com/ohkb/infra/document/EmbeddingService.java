package com.ohkb.infra.document;

import com.ohkb.core.knowledge.KnowledgeArticle;
import com.ohkb.infra.vectorstore.KnowledgeArticleRepository;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import com.ohkb.infra.vectorstore.SemanticCacheRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Embedding 服务 — 文本块向量化并存储到 pgvector。
 * <p>
 * 使用百炼 text-embedding-v3 模型（OpenAI 兼容端点）。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final KnowledgeArticleRepository articleRepo;
    private final PgVectorTemplate pgVectorTemplate;
    private final int batchSize;

    public EmbeddingService(
            @Value("${langchain4j.open-ai.embedding-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.embedding-model.model-name}") String modelName,
            @Value("${app.document.embedding.batch-size:10}") int batchSize,
            KnowledgeArticleRepository articleRepo,
            PgVectorTemplate pgVectorTemplate
    ) {
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        this.articleRepo = articleRepo;
        this.pgVectorTemplate = pgVectorTemplate;
        this.batchSize = batchSize;
    }

    /**
     * 对 TextSegment 列表生成 Embedding 并存储到 pgvector。
     *
     * @param segments   分块列表
     * @param category   分类
     * @param sourceDocId 来源文档 ID
     * @param sourceType  来源类型
     * @return 创建的知识条目数量
     */
    @Transactional
    public int embedAndStore(List<TextSegment> segments, String category,
                             Long sourceDocId, String sourceType) {
        int stored = 0;
        List<TextSegment> batch = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            batch.add(segments.get(i));

            if (batch.size() >= batchSize || i == segments.size() - 1) {
                stored += embedBatch(batch, category, sourceDocId, sourceType);
                batch.clear();
            }
        }

        log.info("[EMBED] Stored {} articles for docId={}", stored, sourceDocId);
        return stored;
    }

    private int embedBatch(List<TextSegment> batch, String category,
                           Long sourceDocId, String sourceType) {
        // 批量生成 Embedding
        List<Embedding> embeddings = embeddingModel.embedAll(batch).content();

        int count = 0;
        for (int i = 0; i < batch.size(); i++) {
            TextSegment segment = batch.get(i);
            Embedding embedding = embeddings.get(i);

            String chunkStatus = segment.metadata().getString("isTable") != null
                    && "true".equals(segment.metadata().getString("isTable"))
                    ? "ok" : "ok";

            KnowledgeArticle article = KnowledgeArticle.create(
                    segment.metadata().getString("heading") != null
                            && !segment.metadata().getString("heading").isEmpty()
                            ? segment.metadata().getString("heading")
                            : "未命名片段",
                    segment.text(),
                    category,
                    sourceType,
                    List.of()
            );

            // 用原生 SQL 写入 pgvector（Spring Data JDBC 不直接支持 VECTOR 类型）
            String vectorStr = embeddingToPgvectorString(embedding);
            pgVectorTemplate.insertArticle(article, vectorStr);

            count++;
        }
        return count;
    }

    /**
     * 将 Embedding 转为 pgvector 兼容的字符串格式。
     */
    public static String embeddingToPgvectorString(Embedding embedding) {
        float[] vector = embedding.vector();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 对单个问题生成 Embedding（用于语义缓存和向量检索）。
     */
    public Embedding embedQuery(String question) {
        return embeddingModel.embed(question).content();
    }
}
