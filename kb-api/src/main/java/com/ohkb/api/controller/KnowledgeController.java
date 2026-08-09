package com.ohkb.api.controller;

import com.ohkb.core.knowledge.KnowledgeArticle;
import com.ohkb.core.knowledge.KnowledgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 REST API。
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 上传文档。
     */
    @PostMapping("/documents/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "general") String category
    ) throws IOException {
        var doc = knowledgeService.uploadDocument(file, category);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "documentId", doc.id() != null ? doc.id() : 0,
                "filename", doc.filename(),
                "message", "文档已上传，后台解析中"
        ));
    }

    /**
     * 手动创建知识条目。
     */
    @PostMapping("/articles")
    public ResponseEntity<KnowledgeArticle> createArticle(@RequestBody CreateArticleRequest request) {
        var article = knowledgeService.createArticle(
                request.title(), request.content(), request.category(),
                request.tags(), "manual");
        return ResponseEntity.ok(article);
    }

    /**
     * 更新知识条目。
     */
    @PutMapping("/articles/{id}")
    public ResponseEntity<KnowledgeArticle> updateArticle(
            @PathVariable Long id, @RequestBody UpdateArticleRequest request) {
        var article = knowledgeService.updateArticle(
                id, request.title(), request.content(), request.category(), request.tags());
        return ResponseEntity.ok(article);
    }

    /**
     * 删除知识条目。
     */
    @DeleteMapping("/articles/{id}")
    public ResponseEntity<Map<String, String>> deleteArticle(@PathVariable Long id) {
        knowledgeService.deleteArticle(id);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "知识条目已删除"));
    }

    /**
     * 版本同步 — 标记相关条目为过时。
     */
    @PostMapping("/version-sync")
    public ResponseEntity<Map<String, Object>> versionSync(@RequestBody VersionSyncRequest request) {
        int count = knowledgeService.markDeprecated(request.module(), request.keywords());
        return ResponseEntity.ok(Map.of("status", "ok", "deprecatedCount", count));
    }

    // ── DTO ──

    public record CreateArticleRequest(String title, String content, String category,
                                       List<String> tags) {}

    public record UpdateArticleRequest(String title, String content, String category,
                                       List<String> tags) {}

    public record VersionSyncRequest(String module, String keywords, String version) {}
}
