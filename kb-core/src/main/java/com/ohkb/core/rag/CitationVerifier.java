package com.ohkb.core.rag;

import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 引用校验器 — LLM 生成回答后逐条对比原文。
 * <p>
 * 每个 citation 对比原文，判断 AI 回答中的陈述是否被原文支持。
 * 结果：YES（准确）/ NO（不准确）/ PARTIALLY（部分准确）。
 */
public class CitationVerifier {

    private static final Logger log = LoggerFactory.getLogger(CitationVerifier.class);

    private static final String VERIFY_PROMPT = """
            判断以下 AI 生成的回答中的陈述是否被引用来源的原文支持。

            AI 回答:
            %s

            引用来源:
            标题: %s
            原文: %s

            只回答 YES、NO 或 PARTIALLY。
            """;

    private final BailianClient llmClient;

    public CitationVerifier(BailianClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 校验 AI 回答中所有的引用来源。
     * 返回标记后的回答（不准确的引用前加 ⚠️ 标记）。
     */
    public String verify(String answer, List<RagResult.Citation> citations) {
        if (citations.isEmpty()) {
            return answer;
        }

        String verifiedAnswer = answer;

        for (RagResult.Citation citation : citations) {
            try {
                String prompt = VERIFY_PROMPT.formatted(answer, citation.title(), citation.snippet());

                BailianClient.ChatResponse resp = llmClient.chat(
                        List.of(BailianClient.userMessage(prompt)),
                        10, 0.0
                );

                String verdict = resp.content().trim().toUpperCase();
                boolean isVerified = verdict.contains("YES");

                if (!isVerified) {
                    log.warn("[VERIFY] Citation '{}' not fully supported: {}", citation.title(), verdict);
                    // 在引用前添加标记
                    verifiedAnswer = verifiedAnswer.replace(
                            citation.title(),
                            "⚠️ " + citation.title() + "（校验结果：" + verdict + "）"
                    );
                }
            } catch (Exception e) {
                log.warn("[VERIFY] Citation check failed for '{}': {}", citation.title(), e.getMessage());
            }
        }

        return verifiedAnswer;
    }
}
