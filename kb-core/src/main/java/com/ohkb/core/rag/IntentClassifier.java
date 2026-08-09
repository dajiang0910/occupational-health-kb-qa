package com.ohkb.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 意图识别 + 问题分类（合并为一次 LLM 调用）。
 * <p>
 * 输出 JSON：{@code {"intent": "question"|"chitchat"|"greeting", "category": "factual"|"howto"|"troubleshoot"}}
 * 使用 Jackson ObjectMapper 进行健壮的 JSON 解析。
 */
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String CLASSIFY_PROMPT = """
            分析用户消息，输出 JSON。
            字段：
            - intent: "question"（系统操作相关问题）/ "chitchat"（闲聊，与系统无关）/ "greeting"（问候语）
            - category: "factual"（事实查询，如某功能在哪、参数含义）/ "howto"（操作指引，如怎么提交）/ "troubleshoot"（故障排查，如为什么报错/失败）

            严格只输出 JSON，不要其他内容。示例输出：
            {"intent":"question","category":"howto"}
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
                    80, 0.0
            );

            String content = resp.content().trim();
            // 清理可能的 markdown 代码块包裹
            if (content.startsWith("```")) {
                content = content.replaceAll("```\\w*\\n?", "").replace("```", "").trim();
            }

            return parseResponse(content);

        } catch (Exception e) {
            log.warn("[INTENT] Classification failed, defaulting to question/howto", e);
            return new Result("question", "howto");
        }
    }

    private Result parseResponse(String content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = mapper.readValue(content, Map.class);

            String intent = map.getOrDefault("intent", "question");
            String category = map.getOrDefault("category", "howto");

            // 验证值合法性
            if (!List.of("question", "chitchat", "greeting").contains(intent)) {
                intent = "question";
            }
            if (!List.of("factual", "howto", "troubleshoot").contains(category)) {
                category = "howto";
            }

            return new Result(intent, category);

        } catch (Exception e) {
            log.warn("[INTENT] JSON parse failed for: \"{}\" — falling back to string matching",
                    content.length() > 60 ? content.substring(0, 60) + "..." : content);

            // Fallback: string matching（兼容非标准 JSON 输出）
            String intent = "question";
            String category = "howto";

            String lower = content.toLowerCase();
            if (lower.contains("chitchat") || lower.contains("闲聊")) intent = "chitchat";
            else if (lower.contains("greeting") || lower.contains("问候")) intent = "greeting";

            if (lower.contains("factual") || lower.contains("事实")) category = "factual";
            else if (lower.contains("troubleshoot") || lower.contains("故障")) category = "troubleshoot";

            return new Result(intent, category);
        }
    }

    public record Result(String intent, String category) {}
}
