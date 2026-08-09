package com.ohkb.core.rag;

import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Confidence 计算器。
 * <p>
 * 公式：confidence = LLM 自评 × llmWeight + 检索 Top-1 相似度 × retrievalWeight
 * 阈值按问题类型差异化。
 */
public class ConfidenceCalculator {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceCalculator.class);

    private static final String SELF_EVAL_PROMPT = """
            评估以下 AI 回答的置信度（0.0-1.0）。

            用户问题: %s
            AI 回答: %s

            评估标准：
            - 1.0: 回答完全准确，引用了具体文档内容
            - 0.7-0.9: 回答基本正确，但部分细节不够精确
            - 0.4-0.6: 回答大致相关，但缺少具体细节
            - 0.0-0.3: 回答可能不准确或与问题不相关

            只输出一个 0.0-1.0 之间的数字，不要其他内容。
            """;

    private final BailianClient llmClient;
    private final double factualThreshold;
    private final double howtoThreshold;
    private final double troubleshootThreshold;
    private final double llmWeight;
    private final double retrievalWeight;

    public ConfidenceCalculator(
            BailianClient llmClient,
            double factualThreshold, double howtoThreshold, double troubleshootThreshold,
            double llmWeight, double retrievalWeight
    ) {
        this.llmClient = llmClient;
        this.factualThreshold = factualThreshold;
        this.howtoThreshold = howtoThreshold;
        this.troubleshootThreshold = troubleshootThreshold;
        this.llmWeight = llmWeight;
        this.retrievalWeight = retrievalWeight;
    }

    /**
     * 计算综合置信度。
     */
    public double compute(String category, String answer, double top1Similarity, String question) {
        // LLM 自评
        double llmSelfEval = getLlmSelfEval(question, answer);

        // 组合评分
        double confidence = llmSelfEval * llmWeight + top1Similarity * retrievalWeight;

        log.info("[CONFIDENCE] category={}, llm_eval={}, top1_sim={}, combined={}",
                category, String.format("%.2f", llmSelfEval),
                String.format("%.2f", top1Similarity), String.format("%.2f", confidence));

        return Math.min(1.0, Math.max(0.0, confidence));
    }

    private double getLlmSelfEval(String question, String answer) {
        try {
            String prompt = SELF_EVAL_PROMPT.formatted(question, answer);
            BailianClient.ChatResponse resp = llmClient.chat(
                    List.of(BailianClient.userMessage(prompt)),
                    10, 0.0
            );
            return Double.parseDouble(resp.content().trim());
        } catch (Exception e) {
            log.warn("[CONFIDENCE] LLM self-eval failed, defaulting to 0.7", e);
            return 0.7;
        }
    }
}
