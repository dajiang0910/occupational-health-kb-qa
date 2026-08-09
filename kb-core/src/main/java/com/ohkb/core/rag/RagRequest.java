package com.ohkb.core.rag;

import java.util.List;
import java.util.Map;

/**
 * RAG Pipeline 请求。
 *
 * @param question    当前用户问题
 * @param history     对话历史（已压缩）
 * @param filters     可选过滤条件（module、tag、role）
 * @param pageContext 主 SaaS 传入的当前页面上下文
 */
public record RagRequest(
        String question,
        List<Message> history,
        Map<String, String> filters,
        String pageContext
) {
    public record Message(String role, String content) {
        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
