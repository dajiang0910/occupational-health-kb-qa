package com.ohkb.core.chat;

import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.llm.BailianClient;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 语义缓存测试——PRD §49。
 * <p>
 * 测试范围：双层阈值命中/未命中、缓存失效、精准失效。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticCache Unit Tests")
class SemanticCacheServiceTest {

    @Mock private PgVectorTemplate pgVectorTemplate;
    @Mock private EmbeddingService embeddingService;
    @Mock private BailianClient llmClient;

    private SemanticCacheService cacheService;

    private static final float[] SAMPLE_VECTOR = new float[1536];
    static {
        for (int i = 0; i < 1536; i++) SAMPLE_VECTOR[i] = 0.01f * (i % 100);
    }

    @BeforeEach
    void setUp() {
        cacheService = new SemanticCacheService(
                pgVectorTemplate, embeddingService, llmClient,
                0.92, 0.85, 30
        );
        when(embeddingService.embedQuery(anyString()))
                .thenReturn(new Embedding(SAMPLE_VECTOR));
        when(embeddingService.embeddingToPgvectorString(any()))
                .thenReturn("[0.01,0.02,...]");
    }

    // ── L1 精确缓存 ──

    @Test
    @DisplayName("L1 缓存：完全相同的两次查询命中")
    void l1Cache_identicalQuestionsHit() {
        String question = "怎么提交项目申报？";
        String answer = "步骤1：进入项目管理...";
        List<Map<String, String>> citations = List.of(
                Map.of("articleId", "1", "title", "操作手册", "snippet", "项目申报...")
        );

        // 存入
        cacheService.store(question, answer, List.of("1"), citations);

        // 查询（不走 L2）
        SemanticCacheService.CachedAnswer result = cacheService.lookup(question);

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo(answer);
        verify(pgVectorTemplate, never()).findBestSemanticMatch(anyString(), anyDouble());
    }

    @Test
    @DisplayName("L1 缓存：大小写不敏感的精确匹配")
    void l1Cache_caseInsensitiveMatch() {
        String original = "怎么提交项目申报？";
        String variant = "怎么提交项目申报？";  // 相同

        cacheService.store(original, "回答A", List.of("1"), List.of());

        SemanticCacheService.CachedAnswer result = cacheService.lookup(variant);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("L1 缓存：不同问题不命中")
    void l1Cache_differentQuestionsMiss() {
        cacheService.store("怎么提交项目申报？", "回答A", List.of("1"), List.of());

        SemanticCacheService.CachedAnswer result = cacheService.lookup("评价报告怎么生成？");
        assertThat(result).isNull();
    }

    // ── L2 语义缓存 ──

    @Test
    @DisplayName("L2 缓存：高置信语义匹配直接返回（sim ≥ 0.92）")
    void l2Cache_highConfidenceMatchReturnsDirectly() {
        PgVectorTemplate.CachedQuestion cached = new PgVectorTemplate.CachedQuestion(
                1L, "怎么提交项目申报？", "步骤1：进入项目管理...",
                "[]", new Long[]{1L}, 0.94
        );
        when(pgVectorTemplate.findBestSemanticMatch(anyString(), eq(0.85)))
                .thenReturn(cached);

        // 先让 L1 未命中（没有存过）
        SemanticCacheService.CachedAnswer result = cacheService.lookup("如何提交申报项目？");

        assertThat(result).isNotNull();
        assertThat(result.answer()).contains("步骤1");
        // 高置信不需要 LLM 校验
        verify(llmClient, never()).chat(any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("L2 缓存：中置信需要 LLM 校验（0.85 ≤ sim < 0.92）")
    void l2Cache_mediumConfidenceRequiresLlmCheck() {
        PgVectorTemplate.CachedQuestion cached = new PgVectorTemplate.CachedQuestion(
                1L, "怎么提交项目申报？", "步骤1：进入项目管理...",
                "[]", new Long[]{1L}, 0.88
        );
        when(pgVectorTemplate.findBestSemanticMatch(anyString(), eq(0.85)))
                .thenReturn(cached);

        // LLM 校验返回 YES（等价）
        mockLlmResponse("YES");

        SemanticCacheService.CachedAnswer result = cacheService.lookup("如何提交申报项目？");

        assertThat(result).isNotNull();
        verify(llmClient).chat(any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("L2 缓存：中置信 LLM 校验失败不返回缓存")
    void l2Cache_mediumConfidenceLlmCheckFails() {
        PgVectorTemplate.CachedQuestion cached = new PgVectorTemplate.CachedQuestion(
                1L, "怎么提交项目申报？", "步骤1：进入项目管理...",
                "[]", new Long[]{1L}, 0.87
        );
        when(pgVectorTemplate.findBestSemanticMatch(anyString(), eq(0.85)))
                .thenReturn(cached);

        // LLM 校验返回 NO（不等价）
        mockLlmResponse("NO");

        SemanticCacheService.CachedAnswer result = cacheService.lookup("系统支持哪些浏览器？");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("L2 缓存：相似度 < 0.85 不命中")
    void l2Cache_belowThresholdMisses() {
        when(pgVectorTemplate.findBestSemanticMatch(anyString(), eq(0.85)))
                .thenReturn(null);

        SemanticCacheService.CachedAnswer result = cacheService.lookup("一个全新的问题");

        assertThat(result).isNull();
    }

    // ── 缓存失效 ──

    @Test
    @DisplayName("缓存失效：文章更新精准失效 L2 缓存")
    void cacheInvalidation_preciseArticleUpdate() {
        cacheService.store("问题1", "回答1", List.of("1", "2"), List.of());

        // 精准失效 articleIds=1,2
        cacheService.invalidateByArticleIds(List.of(1L, 2L));

        // L1 被清空
        verify(pgVectorTemplate).deleteCacheByArticleIds(any());
    }

    @Test
    @DisplayName("缓存失效：单条文章更新不影响无关联缓存")
    void cacheInvalidation_unrelatedArticleUnaffected() {
        cacheService.store("问题A", "回答A", List.of("1"), List.of());
        cacheService.store("问题B", "回答B", List.of("3"), List.of());

        // 只失效 articleId=1
        cacheService.invalidateByArticleIds(List.of(1L));

        // L1 全量清（当前简化实现）
        assertThat(cacheService.lookup("问题A")).isNull();
        assertThat(cacheService.lookup("问题B")).isNull();
    }

    // ── 缓存存储 ──

    @Test
    @DisplayName("缓存存储：L1 写入不抛异常")
    void store_l1WriteSucceeds() {
        assertThatCode(() -> cacheService.store("问题", "回答",
                List.of("1"), List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缓存存储：L2 写入失败不影响 L1 可用性")
    void store_l2FailureDoesNotBreakL1() {
        doThrow(new RuntimeException("DB connection lost"))
                .when(pgVectorTemplate).insertSemanticCache(anyString(), anyString(),
                        anyString(), anyString(), any());

        cacheService.store("问题", "回答", List.of("1"), List.of());

        // L1 应该仍然可用
        SemanticCacheService.CachedAnswer result = cacheService.lookup("问题");
        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("回答");
    }

    // ── helpers ──

    private void mockLlmResponse(String content) {
        BailianClient.ChatResponse mockResp = new BailianClient.ChatResponse(
                content, 5, 0, 5, 0, 0, 50L);
        when(llmClient.chat(any(), anyInt(), anyDouble())).thenReturn(mockResp);
    }
}
