package com.ohkb.api.controller;

import com.ohkb.core.rag.RagPipeline;
import com.ohkb.core.rag.RagRequest;
import com.ohkb.core.rag.RagResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE 流式问答 API — 供 React 嵌入式聊天组件消费。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RagPipeline ragPipeline;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(RagPipeline ragPipeline) {
        this.ragPipeline = ragPipeline;
    }

    /**
     * SSE 流式问答。
     * <p>
     * React SDK 使用 EventSource 连接此端点，接收逐字流式输出。
     * 超时 60 秒（适应 RAG Pipeline 最坏情况延迟）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = "X-Channel", defaultValue = "web") String channel,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
            @RequestBody StreamRequest request
    ) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60 秒超时
        String traceId = MDC.get("traceId");

        sseExecutor.execute(() -> {
            try {
                RagRequest ragRequest = new RagRequest(
                        request.question(),
                        request.history() != null ? request.history().stream()
                                .map(m -> new RagRequest.Message(m.role(), m.content()))
                                .toList() : List.of(),
                        request.filters(),
                        request.pageContext()
                );

                RagResult result = ragPipeline.answer(ragRequest);

                // SSE 事件：逐字发送 answer
                for (int i = 0; i < result.answer().length(); i++) {
                    String chunk = String.valueOf(result.answer().charAt(i));
                    emitter.send(SseEmitter.event()
                            .name("token")
                            .data(chunk));
                    // 模拟流式延迟（真实场景由 StreamingChatLanguageModel 驱动）
                }

                // SSE 事件：元数据（引用、置信度、推荐问题）
                emitter.send(SseEmitter.event()
                        .name("metadata")
                        .data(Map.of(
                                "citations", result.citations(),
                                "confidence", result.confidence(),
                                "relatedQuestions", result.relatedQuestions(),
                                "fallback", result.fallback(),
                                "degrade", result.degrade()
                        )));

                emitter.complete();

                log.info("[CHAT] Stream completed: traceId={}, confidence={}, tokens={}",
                        traceId, result.confidence(), "N/A");

            } catch (Exception e) {
                log.error("[CHAT] Stream error: traceId={}, error={}", traceId, e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", "回答生成失败，请稍后重试")));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        emitter.onCompletion(() -> MDC.put("traceId", traceId));
        emitter.onError(throwable -> MDC.put("traceId", traceId));

        return emitter;
    }

    /**
     * 提交回答反馈（@Async 自动分类归因）。
     */
    @PostMapping("/messages/{messageId}/feedback")
    public Map<String, String> submitFeedback(
            @PathVariable String messageId,
            @RequestBody FeedbackRequest request
    ) {
        log.info("[FEEDBACK] messageId={}, feedback={}, category={}, note={}",
                messageId, request.feedback(), request.category(), request.note());
        // TODO: @Async LLM 自动分类归因
        return Map.of("status", "ok", "messageId", messageId);
    }

    // ── 请求/响应 DTO ──

    public record StreamRequest(
            String question,
            List<SimpleMessage> history,
            Map<String, String> filters,
            String pageContext
    ) {
        public record SimpleMessage(String role, String content) {}
    }

    public record FeedbackRequest(
            String feedback,    // helpful / unhelpful
            String category,    // wrong_answer / missing_knowledge / hard_to_understand / irrelevant / other
            String note
    ) {}
}
