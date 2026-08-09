package com.ohkb.core.knowledge;

import com.ohkb.core.chat.SemanticCacheService;
import com.ohkb.infra.document.DocumentParser;
import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.vectorstore.ImportedDocumentRepository;
import com.ohkb.infra.vectorstore.KnowledgeArticleRepository;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库管理服务。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeArticleRepository articleRepo;
    private final DocumentParser documentParser;
    private final EmbeddingService embeddingService;
    private final SemanticCacheService cacheService;
    private final PgVectorTemplate pgVectorTemplate;
    private final ImportedDocumentRepository documentRepo;
    private final Path uploadDir;

    public KnowledgeService(
            KnowledgeArticleRepository articleRepo,
            DocumentParser documentParser,
            EmbeddingService embeddingService,
            SemanticCacheService cacheService,
            PgVectorTemplate pgVectorTemplate,
            ImportedDocumentRepository documentRepo
    ) throws IOException {
        this.articleRepo = articleRepo;
        this.documentParser = documentParser;
        this.embeddingService = embeddingService;
        this.cacheService = cacheService;
        this.pgVectorTemplate = pgVectorTemplate;
        this.documentRepo = documentRepo;
        this.uploadDir = Files.createTempDirectory("ohkb-uploads");
    }

    /**
     * 上传文档并异步解析。
     */
    @Transactional
    public ImportedDocument uploadDocument(MultipartFile file, String category) throws IOException {
        String filename = file.getOriginalFilename();
        String fileType = getFileType(filename);
        long size = file.getSize();

        ImportedDocument doc = ImportedDocument.create(filename, fileType, size);
        ImportedDocument saved = documentRepo.save(doc);
        log.info("[KB] Document uploaded: id={}, filename={}, size={} bytes",
                saved.id(), filename, size);

        // 更新状态为 parsing
        documentRepo.updateStatus(saved.id(), "parsing");

        // 保存到临时目录
        Path filePath = uploadDir.resolve(UUID.randomUUID() + "_" + filename);
        file.transferTo(filePath);

        // 异步解析和嵌入
        processDocumentAsync(saved, filePath, category);

        return saved;
    }

    /**
     * 异步解析文档：解析 → 分块 → 嵌入 → 存储。
     */
    @Async
    @Transactional
    public void processDocumentAsync(ImportedDocument doc, Path filePath, String category) {
        try {
            log.info("[KB] Processing document: id={}, filename={}", doc.id(), doc.filename());

            // 状态：parsing → chunking
            documentRepo.updateStatus(doc.id(), "chunking");

            // 解析 + 结构感知分块
            List<TextSegment> segments = documentParser.parse(filePath);

            // 统计失败分块
            long failedCount = segments.stream()
                    .filter(s -> "parse_failed".equals(s.metadata().getString("chunkStatus")))
                    .count();

            // 状态：chunking → embedding
            documentRepo.updateStatus(doc.id(), "embedding");

            // 生成 Embedding 并存储
            int stored = embeddingService.embedAndStore(segments, category, doc.id(), "document_import");

            // 状态：embedding → completed
            documentRepo.updateProcessingResult(doc.id(), "completed", stored, (int) failedCount);

            log.info("[KB] Document processed: id={}, {} chunks stored, {} failed",
                    doc.id(), stored, failedCount);

            // 清理临时文件
            Files.deleteIfExists(filePath);

        } catch (Exception e) {
            log.error("[KB] Document processing failed: id={}, filename={}",
                    doc.id(), doc.filename(), e);
            documentRepo.updateError(doc.id(), e.getMessage(),
                    "Processing failed at " + java.time.Instant.now());
        }
    }

    /**
     * 手动创建知识条目。
     */
    @Transactional
    public KnowledgeArticle createArticle(String title, String content, String category,
                                          List<String> tags, String sourceType) {
        KnowledgeArticle article = KnowledgeArticle.create(title, content, category, sourceType, tags);
        KnowledgeArticle saved = articleRepo.save(article);
        log.info("[KB] Article created: id={}, title={}", saved.id(), title);

        // 异步生成 Embedding
        generateEmbeddingAsync(saved.id(), content);

        return saved;
    }

    /**
     * 更新知识条目（精准失效语义缓存）。
     */
    @Transactional
    public KnowledgeArticle updateArticle(Long id, String title, String content,
                                          String category, List<String> tags) {
        KnowledgeArticle existing = articleRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + id));

        // 精准失效语义缓存
        if (existing.id() != null) {
            cacheService.invalidateByArticleIds(List.of(existing.id()));
        }

        // 更新（创建新 Record）
        KnowledgeArticle updated = new KnowledgeArticle(
                existing.id(), title, content, existing.contentJson(),
                category, tags, existing.sourceType(), existing.sourceDocId(),
                existing.chunkIndex(), existing.chunkStatus(), existing.embeddingModel(),
                existing.effectiveFrom(), existing.effectiveUntil(), existing.isDeprecated(),
                existing.hitCount(), existing.helpfulCount(), existing.unhelpfulCount(),
                existing.createdAt(), java.time.Instant.now(), existing.similarity()
        );
        KnowledgeArticle saved = articleRepo.save(updated);
        log.info("[KB] Article updated: id={}", id);

        // 异步重新生成 Embedding
        generateEmbeddingAsync(id, content);

        return saved;
    }

    /**
     * 删除知识条目。
     */
    @Transactional
    public void deleteArticle(Long id) {
        articleRepo.findById(id).ifPresent(a -> {
            cacheService.invalidateByArticleIds(List.of(a.id()));
            articleRepo.delete(a);
            log.info("[KB] Article deleted: id={}", id);
        });
    }

    /**
     * 批量标记过时（版本同步）。
     */
    @Transactional
    public int markDeprecated(String module, String keywords) {
        // 简单实现：按分类标记
        List<KnowledgeArticle> articles = articleRepo.findByCategoryActive(module);
        List<Long> ids = articles.stream().map(KnowledgeArticle::id).toList();
        if (!ids.isEmpty()) {
            articleRepo.markDeprecated(ids);
            cacheService.invalidateByArticleIds(ids);
        }
        log.info("[KB] Marked {} articles as deprecated for module={}", ids.size(), module);
        return ids.size();
    }

    // ── private ──

    @Async
    void generateEmbeddingAsync(Long articleId, String content) {
        try {
            log.debug("[KB] Generating embedding for articleId={}", articleId);
            var embedding = embeddingService.embedQuery(content);
            String vectorStr = EmbeddingService.embeddingToPgvectorString(embedding);
            pgVectorTemplate.updateArticleEmbedding(articleId, vectorStr);
            log.info("[KB] Embedding stored for articleId={}", articleId);
        } catch (Exception e) {
            log.error("[KB] Embedding generation failed for articleId={}: {}", articleId, e.getMessage());
            // TODO: dead letter 表记录失败任务
        }
    }

    private String getFileType(String filename) {
        if (filename == null) return "unknown";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".md")) return "md";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".xlsx")) return "xlsx";
        return "unknown";
    }
}
