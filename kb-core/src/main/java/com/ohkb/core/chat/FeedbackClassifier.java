package com.ohkb.core.chat;

import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 反馈自动分类——@Async LLM 调用。
 * <p>
 * 当用户点踩时，异步分析反馈文本，自动归类到五类之一。
 * 用于知识盲区分析和优先级排序。
 */
@Service
public class FeedbackClassifier {

    private static final Logger log = LoggerFactory.getLogger(FeedbackClassifier.class);

    private static final String CLASSIFY_PROMPT = """
            分析用户对 AI 回答的负面反馈，归类到以下五个类别之一。

            类别定义：
            - wrong_answer: 回答内容错误或与事实不符
            - missing_knowledge: 知识库中缺少相关信息，AI 无法回答
            - hard_to_understand: 回答内容正确但表达不清晰、难以理解
            - irrelevant: 回答内容与用户问题无关，答非所问
            - other: 以上都不符合

            只输出类别名称（如 "wrong_answer"），不要其他内容。
            """;

    private final BailianClient llmClient;

    public FeedbackClassifier(BailianClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 异步分类用户反馈。
     *
     * @param question     用户原始问题
     * @param aiAnswer     AI 的回答
     * @param feedbackNote 用户反馈文本（可选）
     * @return 分类结果 Future
     */
    @Async
    public CompletableFuture<String> classify(String question, String aiAnswer, String feedbackNote) {
        try {
            String prompt = CLASSIFY_PROMPT + String.format("""

                    用户问题：%s

                    AI 回答：%s

                    用户反馈：%s
                    """,
                    truncate(question, 200),
                    truncate(aiAnswer, 500),
                    feedbackNote != null && !feedbackNote.isBlank()
                            ? truncate(feedbackNote, 200)
                            : "（无具体反馈文本）");

            BailianClient.ChatResponse resp = llmClient.chat(
                    List.of(BailianClient.userMessage(prompt)),
                    15, 0.0
            );

            String category = resp.content().trim().toLowerCase();
            log.info("[FEEDBACK] Classified: question=\"{}\" → category={}",
                    truncate(question, 50), category);

            // 验证类别有效性
            List<String> validCategories = List.of(
                    "wrong_answer", "missing_knowledge",
                    "hard_to_understand", "irrelevant", "other"
            );

            if (validCategories.contains(category)) {
                return CompletableFuture.completedFuture(category);
            }

            // 模糊匹配
            for (String valid : validCategories) {
                if (category.contains(valid)) {
                    return CompletableFuture.completedFuture(valid);
                }
            }

            log.warn("[FEEDBACK] Unknown category: \"{}\", defaulting to other", category);
            return CompletableFuture.completedFuture("other");

        } catch (Exception e) {
            log.error("[FEEDBACK] Classification failed: {}", e.getMessage());
            return CompletableFuture.completedFuture("other");
        }
    }

    /**
     * 批量分类反馈（用于离线分析）。
     */
    @Async
    public CompletableFuture<List<String>> classifyBatch(
            List<String> questions, List<String> answers, List<String> notes) {
        List<String> results = new java.util.ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            try {
                String category = classify(questions.get(i), answers.get(i),
                        i < notes.size() ? notes.get(i) : null).get();
                results.add(category);
            } catch (Exception e) {
                results.add("other");
            }
        }
        return CompletableFuture.completedFuture(results);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
