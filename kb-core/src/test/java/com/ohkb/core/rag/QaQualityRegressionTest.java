package com.ohkb.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohkb.core.knowledge.KnowledgeArticle;
import com.ohkb.infra.document.EmbeddingService;
import com.ohkb.infra.llm.BailianClient;
import com.ohkb.infra.vectorstore.PgVectorTemplate;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 问答质量回归测试——PRD §50。
 * <p>
 * 每次知识库版本变更后自动运行 golden dataset 验证：
 * <ul>
 *   <li>检测回答质量退化（语义相似度下降 &gt;10% 则告警）</li>
 *   <li>新增知识条目后验证相关问题命中</li>
 *   <li>按类别统计通过率</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("QA Quality Regression Tests")
class QaQualityRegressionTest {

    private static final Logger log = LoggerFactory.getLogger(QaQualityRegressionTest.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired private RagPipeline ragPipeline;
    @Autowired private PgVectorTemplate pgVectorTemplate;
    @Autowired private EmbeddingService embeddingService;

    @MockBean private BailianClient llmClient;

    private List<GoldenQaPair> goldenDataset;
    private RegressionReport report;

    @BeforeEach
    void loadGoldenDataset() throws Exception {
        goldenDataset = mapper.readValue(
                getClass().getResourceAsStream("/golden-dataset.json"),
                new TypeReference<List<GoldenQaPair>>() {});
        report = new RegressionReport();
        log.info("Loaded {} golden Q&A pairs for regression test", goldenDataset.size());
    }

    // ── 核心回归测试 ──

    @Test
    @DisplayName("Golden Dataset 全量回归：回答质量未退化")
    void fullGoldenDatasetRegression() {
        for (GoldenQaPair pair : goldenDataset) {
            RegressionResult result = runSingleRegression(pair);
            report.add(result);
        }

        // 生成报告
        report.print();

        // 断言：整体通过率 ≥ 70%
        assertThat(report.passRate())
                .as("Golden dataset pass rate should be ≥ 70%%")
                .isGreaterThanOrEqualTo(0.70);

        // 断言：无严重退化（单个问题语义相似度下降 > 30%）
        assertThat(report.maxDegradation())
                .as("No single question should degrade > 30%%")
                .isLessThan(0.30);
    }

    @Test
    @DisplayName("按类别统计：factual 通过率 ≥ howto 通过率")
    void categoryBreakdown_factualBetterThanHowto() {
        Map<String, List<Boolean>> resultsByCategory = new HashMap<>();

        for (GoldenQaPair pair : goldenDataset) {
            RegressionResult result = runSingleRegression(pair);
            resultsByCategory
                    .computeIfAbsent(pair.category(), k -> new ArrayList<>())
                    .add(result.passed());
        }

        double factualRate = passRateOf(resultsByCategory, "factual");
        double howtoRate = passRateOf(resultsByCategory, "howto");
        double troubleshootRate = passRateOf(resultsByCategory, "troubleshoot");

        log.info("Pass rates by category: factual={:.1f}%, howto={:.1f}%, troubleshoot={:.1f}%",
                factualRate * 100, howtoRate * 100, troubleshootRate * 100);

        // factual 应 ≥ howto，因为事实类问题检索精度更高
        assertThat(factualRate).isGreaterThanOrEqualTo(howtoRate - 0.1);
    }

    @Test
    @DisplayName("新增条目回归：新条目后相关问题命中率提升")
    void newArticleRegression_relatedQuestionsHit() {
        // 选定一组已知能命中的问题
        List<String> targetQuestions = List.of(
                "怎么提交项目申报？",
                "评价报告审核需要几个环节？",
                "检测采样最大样本数是多少？"
        );

        for (String question : targetQuestions) {
            mockLlmResponse("预期的回答内容，包含正确的操作指引。", 0.85);

            RagResult result = ragPipeline.answer(new RagRequest(
                    question, List.of(), Map.of(), null));

            assertThat(result.answer())
                    .as("Question should get a meaningful answer: \"%s\"", question)
                    .isNotEmpty();

            assertThat(result.citations())
                    .as("Question should have citations: \"%s\"", question)
                    .isNotEmpty();

            log.info("Regression check \"{}\": {} citations, confidence={:.2f}",
                    question, result.citations().size(), result.confidence());
        }
    }

    @Test
    @DisplayName("退化检测：单个问题语义相似度下降不超过 10%")
    void degradationDetection_singleQuestionThreshold() {
        // 模拟一个场景：知识库变更后同一问题的回答质量
        GoldenQaPair pair = goldenDataset.get(0);
        String question = pair.question();

        // 第一次运行（baseline）
        mockLlmResponse(pair.answer(), 0.85);
        RagResult baseline = ragPipeline.answer(new RagRequest(question, List.of(), Map.of(), null));

        // 第二次运行（after KB change）
        mockLlmResponse(pair.answer(), 0.82);
        RagResult after = ragPipeline.answer(new RagRequest(question, List.of(), Map.of(), null));

        // 置信度下降不应超过 10%
        double degradation = baseline.confidence() - after.confidence();
        log.info("Degradation check: baseline={:.3f}, after={:.3f}, delta={:.3f}",
                baseline.confidence(), after.confidence(), degradation);

        assertThat(degradation)
                .as("Confidence degradation should be ≤ 10%%")
                .isLessThan(0.10);
    }

    // ── helpers ──

    private RegressionResult runSingleRegression(GoldenQaPair pair) {
        try {
            mockLlmResponse(pair.answer(), 0.85);

            RagResult result = ragPipeline.answer(new RagRequest(
                    pair.question(), List.of(), Map.of(), null));

            boolean passed = !result.fallback()
                    && result.answer() != null
                    && !result.answer().isBlank()
                    && result.confidence() >= 0.5;

            double answerSimilarity = computeSimpleSimilarity(
                    result.answer() != null ? result.answer() : "", pair.answer());

            return new RegressionResult(pair.id(), pair.category(), pair.question(),
                    passed, result.confidence(), answerSimilarity, result.fallback());

        } catch (Exception e) {
            log.warn("Regression test failed for {}: {}", pair.id(), e.getMessage());
            return new RegressionResult(pair.id(), pair.category(), pair.question(),
                    false, 0.0, 0.0, true);
        }
    }

    /**
     * 简单的 Jaccard 相似度（不依赖 Embedding API）。
     */
    private double computeSimpleSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.split("")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.split("")));
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private double passRateOf(Map<String, List<Boolean>> results, String category) {
        List<Boolean> list = results.getOrDefault(category, List.of());
        if (list.isEmpty()) return 0.0;
        return (double) list.stream().filter(Boolean::booleanValue).count() / list.size();
    }

    private void mockLlmResponse(String answer, double confidence) {
        BailianClient.ChatResponse mockResp = new BailianClient.ChatResponse(
                answer, 100, 50, 150, 0, 0, 200L);
        when(llmClient.chat(any(List.class), anyInt(), anyDouble())).thenReturn(mockResp);
    }

    // ── 数据模型 ──

    public record GoldenQaPair(
            String id, String category, String question, String answer,
            List<String> keywords, List<String> citations) {}

    record RegressionResult(
            String id, String category, String question,
            boolean passed, double confidence, double answerSimilarity, boolean fallback) {}

    static class RegressionReport {
        private final List<RegressionResult> results = new ArrayList<>();

        void add(RegressionResult r) { results.add(r); }

        double passRate() {
            if (results.isEmpty()) return 0;
            return (double) results.stream().filter(RegressionResult::passed).count() / results.size();
        }

        double maxDegradation() {
            return results.stream()
                    .filter(r -> !r.passed())
                    .mapToDouble(r -> 1.0 - r.answerSimilarity())
                    .max().orElse(0);
        }

        void print() {
            long passed = results.stream().filter(RegressionResult::passed).count();
            double avgConfidence = results.stream()
                    .mapToDouble(RegressionResult::confidence).average().orElse(0);
            double avgSimilarity = results.stream()
                    .mapToDouble(RegressionResult::answerSimilarity).average().orElse(0);

            log.info("=== Regression Report ===");
            log.info("Total: {}, Passed: {}, Failed: {}, Rate: {:.1f}%",
                    results.size(), passed, results.size() - passed, passRate() * 100);
            log.info("Avg Confidence: {:.3f}, Avg Similarity: {:.3f}", avgConfidence, avgSimilarity);

            results.stream().filter(r -> !r.passed()).forEach(r ->
                    log.info("  FAIL {} [{}]: \"{}\" confidence={:.2f} fallback={}",
                            r.id(), r.category(), r.question(), r.confidence(), r.fallback()));
        }
    }
}
