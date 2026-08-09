package com.ohkb.core.rag;

import java.util.List;

/**
 * RAG Pipeline 响应。
 *
 * @param answer           Markdown 格式回答
 * @param citations        引用来源（含 articleId）
 * @param confidence       置信度（LLM 自评×0.4 + 检索 Top-1×0.6）
 * @param relatedQuestions 推荐问题列表
 * @param articleIds       命中知识条目 ID，用于语义缓存精准失效
 * @param fallback         是否触发转人工
 * @param degrade          是否触发降级（LLM 不可用，走纯检索或缓存）
 */
public record RagResult(
        String answer,
        List<Citation> citations,
        double confidence,
        List<String> relatedQuestions,
        List<String> articleIds,
        boolean fallback,
        boolean degrade
) {
    public record Citation(
            String articleId,
            String title,
            String snippet,
            boolean verified
    ) {}

    /**
     * 创建降级结果（LLM 不可用，仅返回检索到的原文）。
     */
    public static RagResult degraded(List<Citation> citations) {
        var docs = citations.stream()
                .map(c -> "> " + c.snippet())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("未找到相关文档");
        return new RagResult(
                "以下是与您问题相关的文档，请查看：\n\n" + docs,
                citations, 0.0, List.of(), List.of(), false, true
        );
    }

    /**
     * 创建转人工结果。
     */
    public static RagResult fallback(String partialAnswer, List<Citation> citations) {
        return new RagResult(
                partialAnswer, citations, 0.0, List.of(), List.of(), true, false
        );
    }
}
