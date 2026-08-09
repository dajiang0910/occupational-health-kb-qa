package com.ohkb.core.rag;

/**
 * RAG Pipeline 核心接口。
 * 所有问答通道（Web Chat API、企业微信 Webhook）统一通过此接口调用。
 */
public interface RagPipeline {

    /**
     * 处理用户问题并返回 RAG 结果。
     *
     * @param request 包含问题、历史、过滤条件的请求
     * @return RAG 结果（回答 + 引用 + 置信度 + 是否转人工/降级）
     */
    RagResult answer(RagRequest request);
}
