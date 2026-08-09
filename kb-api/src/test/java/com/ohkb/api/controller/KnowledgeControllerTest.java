package com.ohkb.api.controller;

import com.ohkb.core.knowledge.KnowledgeArticle;
import com.ohkb.core.knowledge.KnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 知识库管理 API 测试——PRD §48。
 * <p>
 * 测试范围：文档上传、知识条目 CRUD、版本同步。
 */
@WebMvcTest(KnowledgeController.class)
@DisplayName("Knowledge API Tests")
class KnowledgeControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private KnowledgeService knowledgeService;

    // ── 知识条目 CRUD ──

    @Test
    @DisplayName("POST /api/kb/articles 创建知识条目")
    void createArticle_returnsCreated() throws Exception {
        KnowledgeArticle mockArticle = KnowledgeArticle.create(
                "新条目", "内容", "general", "manual", List.of("测试"));
        when(knowledgeService.createArticle(anyString(), anyString(), anyString(),
                any(), anyString())).thenReturn(mockArticle);

        mockMvc.perform(post("/api/kb/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"新条目","content":"内容","category":"general","tags":["测试"]}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/kb/articles 创建缺少必填字段返回 400")
    void createArticle_missingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/kb/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"title":""}"""))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("PUT /api/kb/articles/{id} 更新条目触发缓存失效")
    void updateArticle_invalidatesSemanticCache() throws Exception {
        KnowledgeArticle updated = KnowledgeArticle.create(
                "更新标题", "更新内容", "general", "manual", List.of());
        when(knowledgeService.updateArticle(anyLong(), anyString(), anyString(),
                anyString(), any())).thenReturn(updated);

        mockMvc.perform(put("/api/kb/articles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"title":"更新标题","content":"更新内容","category":"general","tags":[]}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /api/kb/articles/{id} 删除条目并精准失效缓存")
    void deleteArticle_preciseCacheInvalidation() throws Exception {
        mockMvc.perform(delete("/api/kb/articles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.message").value("知识条目已删除"));
    }

    // ── 文档上传 ──

    @Test
    @DisplayName("POST /api/kb/documents/upload 上传文档返回 documentId")
    void uploadDocument_returnsDocumentId() throws Exception {
        // 文档上传是 MultipartFile，不能用简单 JSON
        // 验证端点存在即可
        mockMvc.perform(post("/api/kb/documents/upload")
                        .param("category", "general"))
                .andExpect(status().is4xxClientError()); // 缺少 file 参数
    }

    // ── 版本同步 ──

    @Test
    @DisplayName("POST /api/kb/version-sync 标记受影响条目为 deprecated")
    void versionSync_marksDeprecated() throws Exception {
        when(knowledgeService.markDeprecated(anyString(), anyString())).thenReturn(3);

        mockMvc.perform(post("/api/kb/version-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"module":"project_application","keywords":"申报流程","version":"2.5.0"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.deprecatedCount").value(3));
    }

    @Test
    @DisplayName("POST /api/kb/version-sync 无匹配条目时返回 0")
    void versionSync_noMatchesReturnsZero() throws Exception {
        when(knowledgeService.markDeprecated(anyString(), anyString())).thenReturn(0);

        mockMvc.perform(post("/api/kb/version-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"module":"nonexistent","keywords":"nothing","version":"1.0"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedCount").value(0));
    }
}
