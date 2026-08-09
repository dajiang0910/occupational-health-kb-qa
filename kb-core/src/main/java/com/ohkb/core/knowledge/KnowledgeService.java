package com.ohkb.core.knowledge;

import com.ohkb.core.chat.SemanticCacheService;
import com.ohkb.infra.document.DocumentParser;
import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.vectorstore.KnowledgeArticleRepository;
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
    private final Path uploadDir;

    public KnowledgeService(
            KnowledgeArticleRepository articleRepo,
            DocumentParser documentParser,
            EmbeddingService embeddingService,
            SemanticCacheService cacheService
    ) throws IOException {
        this.articleRepo = articleRepo;
        this.documentParser = documentParser;
        this.embeddingService = embeddingService;
        this.cacheService = cacheService;
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
        // TODO: 持久化 imported_documents 表
        log.info("[KB] Document uploaded: {} ({} bytes)", filename, size);

        // 保存到临时目录
        Path filePath = uploadDir.resolve(UUID.randomUUID() + "_" + filename);
        file.transferTo(filePath);

        // 异步解析和嵌入
        processDocumentAsync(doc, filePath, category);

        return doc;
    }

    /**
     * 异步解析文档：解析 → 分块 → 嵌入 → 存储。
     */
    @Async
    @Transactional
    public void processDocumentAsync(ImportedDocument doc, Path filePath, String category) {
        try {
            log.info("[KB] Processing document: {}", doc.filename());

            // 解析 + 结构感知分块
            List<TextSegment> segments = documentParser.parse(filePath);

            // 统计失败分块
            long failedCount = segments.stream()
                    .filter(s -> "parse_failed".equals(s.metadata().getString("chunkStatus")))
                    .count();

            // 生成 Embedding 并存储
            int stored = embeddingService.embedAndStore(segments, category, doc.id(), "document_import");

            log.info("[KB] Document processed: {} chunks stored, {} failed", stored, failedCount);

            // 清理临时文件
            Files.deleteIfExists(filePath);

        } catch (Exception e) {
            log.error("[KB] Document processing failed: {}", doc.filename(), e);
            // TODO: 更新 imported_documents 状态为 failed
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
                existing.createdAt(), java.time.Instant.now()
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
        // Phase 2: 异步生成单条 embedding
        log.debug("[KB] Embedding generation queued for articleId={}", articleId);
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
