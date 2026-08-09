package com.ohkb.core.rag;

import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 意图识别 + 问题分类（合并为一次 LLM 调用）。
 * <p>
 * 输出：{intent: "question"|"chitchat"|"greeting", category: "factual"|"howto"|"troubleshoot"}
 */
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final String CLASSIFY_PROMPT = """
            分析用户消息，输出 JSON。
            字段：
            - intent: "question"（系统相关问题）/ "chitchat"（闲聊）/ "greeting"（问候）
            - category: "factual"（事实查询，如某功能在哪）/ "howto"（操作指引）/ "troubleshoot"（故障排查）

            只输出 JSON，不要其他内容。
            """;

    private final BailianClient llmClient;

    public IntentClassifier(BailianClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 分类用户消息。
     */
    public Result classify(String userMessage) {
        try {
            String prompt = CLASSIFY_PROMPT + "\n\n用户消息: " + userMessage;

            BailianClient.ChatResponse resp = llmClient.chat(
                    List.of(BailianClient.userMessage(prompt)),
                    50, 0.0
            );

            // 简单解析 JSON（Phase 2: 用 structured output / JSON Schema）
            String content = resp.content().trim();
            return parseResponse(content);

        } catch (Exception e) {
            log.warn("[INTENT] Classification failed, defaulting to question/howto", e);
            return new Result("question", "howto");
        }
    }

    private Result parseResponse(String content) {
        String intent = "question";
        String category = "howto";

        if (content.contains("\"intent\"")) {
            if (content.contains("\"chitchat\"")) intent = "chitchat";
            else if (content.contains("\"greeting\"")) intent = "greeting";
        }

        if (content.contains("\"category\"")) {
            if (content.contains("\"factual\"")) category = "factual";
            else if (content.contains("\"troubleshoot\"")) category = "troubleshoot";
        }

        return new Result(intent, category);
    }

    public record Result(String intent, String category) {}
}
