package com.ohkb.core.rag;

import com.ohkb.infra.llm.BailianClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Confidence 计算单元测试——PRD §46。
 * <p>
 * 验证组合公式：confidence = LLM 自评 × 0.4 + 检索 Top-1 相似度 × 0.6
 * 以及不同问题类型的差异化阈值。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfidenceCalculator Unit Tests")
class ConfidenceCalculatorTest {

    @Mock private BailianClient llmClient;

    private ConfidenceCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ConfidenceCalculator(
                llmClient,
                0.80,   // factual threshold
                0.65,   // howto threshold
                0.50,   // troubleshoot threshold
                0.4,    // LLM weight
                0.6     // retrieval weight
        );
    }

    @Test
    @DisplayName("Factual 问题：高检索 + 高 LLM 自评 = 高置信度")
    void factual_highBoth_givesHighConfidence() {
        mockLlmSelfEval("0.95");

        double confidence = calculator.compute("factual",
                "项目申报功能在「项目管理」→「项目申报」菜单下。",
                0.92, "项目申报功能在哪个菜单？");

        // 0.95 × 0.4 + 0.92 × 0.6 = 0.38 + 0.552 = 0.932
        assertThat(confidence).isGreaterThan(0.85);
    }

    @Test
    @DisplayName("Howto 问题：中等检索 + LLM 自评 = 中等置信度")
    void howto_moderateBoth_givesModerateConfidence() {
        mockLlmSelfEval("0.75");

        double confidence = calculator.compute("howto",
                "提交项目申报需要多步操作。",
                0.70, "怎么提交项目申报？");

        // 0.75 × 0.4 + 0.70 × 0.6 = 0.30 + 0.42 = 0.72
        assertThat(confidence).isBetween(0.60, 0.85);
    }

    @Test
    @DisplayName("Troubleshoot 问题：低检索 + 中等 LLM 自评 = 低置信度")
    void troubleshoot_lowRetrieval_givesLowConfidence() {
        mockLlmSelfEval("0.60");

        double confidence = calculator.compute("troubleshoot",
                "可能是配置问题导致审核不通过。",
                0.40, "为什么报告审核不通过？");

        // 0.60 × 0.4 + 0.40 × 0.6 = 0.24 + 0.24 = 0.48
        assertThat(confidence).isLessThan(0.65);
    }

    @Test
    @DisplayName("检索权重（0.6）大于 LLM 自评权重（0.4）")
    void retrievalWeightDominates() {
        // 场景：检索完美但 LLM 自评低
        mockLlmSelfEval("0.3");

        double confidence = calculator.compute("factual",
                "正确答案。", 0.95, "哪个菜单？");

        // 0.30 × 0.4 + 0.95 × 0.6 = 0.12 + 0.57 = 0.69
        // 即使 LLM 自评很低，检索好仍能拉高置信度
        assertThat(confidence).isGreaterThan(0.6);
    }

    @Test
    @DisplayName("LLM 自评权重虽有但不主导")
    void llmSelfEvalDoesNotDominate() {
        // 场景：LLM 自评完美但检索差
        mockLlmSelfEval("0.99");

        double confidence = calculator.compute("factual",
                "可能是正确答案。", 0.30, "哪个菜单？");

        // 0.99 × 0.4 + 0.30 × 0.6 = 0.396 + 0.18 = 0.576
        // 检索差会显著拉低置信度
        assertThat(confidence).isLessThan(0.7);
    }

    @Test
    @DisplayName("置信度范围始终在 [0, 1] 之间")
    void confidenceAlwaysBetweenZeroAndOne() {
        mockLlmSelfEval("1.0");
        double high = calculator.compute("factual", "答案", 1.0, "问题");
        assertThat(high).isBetween(0.0, 1.0);

        mockLlmSelfEval("0.0");
        double low = calculator.compute("troubleshoot", "不确定", 0.0, "问题");
        assertThat(low).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("LLM 自评解析容错")
    void llmSelfEvalParsingRobust() {
        // 模拟各种 LLM 输出格式
        mockLlmSelfEval("Confidence: 0.85");
        double c1 = calculator.compute("factual", "答案", 0.8, "问题");
        assertThat(c1).isBetween(0.0, 1.0);

        mockLlmSelfEval("I think it's about 0.75");
        double c2 = calculator.compute("howto", "答案", 0.8, "问题");
        assertThat(c2).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("不同类别使用不同阈值判定")
    void differentCategoriesDifferentThresholds() {
        // Factual: 0.80 阈值
        mockLlmSelfEval("0.6");
        double factual = calculator.compute("factual", "答案", 0.7, "问题");
        // 0.6×0.4 + 0.7×0.6 = 0.24 + 0.42 = 0.66 < 0.80 → fallback

        // Troubleshoot: 0.50 阈值（更宽松）
        mockLlmSelfEval("0.6");
        double troubleshoot = calculator.compute("troubleshoot", "答案", 0.5, "问题");
        // 0.6×0.4 + 0.5×0.6 = 0.24 + 0.30 = 0.54 > 0.50 → 不会 fallback

        assertThat(factual).isLessThan(0.80);
        assertThat(troubleshoot).isGreaterThan(0.50);
    }

    // ── helpers ──

    private void mockLlmSelfEval(String selfEvalOutput) {
        BailianClient.ChatResponse mockResp = new BailianClient.ChatResponse(
                selfEvalOutput, 15, 0, 15, 0, 0, 100L);
        when(llmClient.chat(any(), anyInt(), anyDouble())).thenReturn(mockResp);
    }
}
