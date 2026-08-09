package com.ohkb.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 百炼 API 客户端（原生 OkHttp，支持 cache_control 注入）。
 * <p>
 * LangChain4j 的 OpenAI 兼容端点不直接暴露 cache_control，
 * 因此在 RAG Pipeline 中直接使用此客户端调用 LLM，绕过 LangChain4j 的 HTTP 限制。
 */
@Component
public class BailianClient {

    private static final Logger log = LoggerFactory.getLogger(BailianClient.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public BailianClient(
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String model,
            @Value("${langchain4j.open-ai.chat-model.timeout}") java.time.Duration timeout,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    /**
     * 调用百炼 Chat Completions API（非流式）。
     *
     * @param messages 消息列表，content 字段支持 String 或 List<ContentPart>
     *                 当 content 是 List 时，可以包含 cache_control 标记
     */
    @SuppressWarnings("unchecked")
    public ChatResponse chat(List<Map<String, Object>> messages, int maxTokens, double temperature)
            throws IOException {

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", maxTokens,
                "temperature", temperature
        );

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(body),
                        MediaType.parse("application/json")))
                .build();

        long start = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - start;

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("[BAILIAN] API error {}: {}", response.code(), errorBody);
                throw new IOException("Bailian API error " + response.code() + ": " + errorBody);
            }

            Map<String, Object> result = objectMapper.readValue(
                    response.body().string(), Map.class);

            Map<String, Object> usage = (Map<String, Object>) result.getOrDefault("usage", Map.of());
            Map<String, Object> promptDetails = (Map<String, Object>) usage.getOrDefault(
                    "prompt_tokens_details", Map.of());

            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            String content = "";
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                content = (String) message.getOrDefault("content", "");
            }

            return new ChatResponse(
                    content,
                    ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue(),
                    ((Number) usage.getOrDefault("completion_tokens", 0)).intValue(),
                    ((Number) usage.getOrDefault("total_tokens", 0)).intValue(),
                    ((Number) promptDetails.getOrDefault("cached_tokens", 0)).intValue(),
                    ((Number) promptDetails.getOrDefault("cache_creation_input_tokens", 0)).intValue(),
                    latency
            );
        }
    }

    /**
     * 构建带 cache_control 标记的 system 消息。
     * 用于显式 Prompt Caching：将 system prompt 标记为可缓存。
     */
    public static Map<String, Object> systemWithCache(String text) {
        return Map.of(
                "role", "system",
                "content", List.of(
                        Map.of("type", "text", "text", text,
                               "cache_control", Map.of("type", "ephemeral"))
                )
        );
    }

    /**
     * 构建带 cache_control 标记的 user 消息（固定知识部分缓存，问题部分不缓存）。
     */
    public static Map<String, Object> userWithCache(String cachedPart, String uncachedQuestion) {
        return Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", cachedPart,
                               "cache_control", Map.of("type", "ephemeral")),
                        Map.of("type", "text", "text", uncachedQuestion)
                )
        );
    }

    /**
     * 构建普通 user 消息（无缓存）。
     */
    public static Map<String, Object> userMessage(String text) {
        return Map.of("role", "user", "content", text);
    }

    /**
     * Chat Completions 响应。
     */
    public record ChatResponse(
            String content,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int cachedTokens,
            int cacheCreationTokens,
            long latencyMs
    ) {
        public double cacheHitRate() {
            return promptTokens > 0 ? (double) cachedTokens / promptTokens : 0.0;
        }
    }
}
