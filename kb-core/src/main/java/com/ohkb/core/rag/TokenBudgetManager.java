package com.ohkb.core.rag;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Token 预算管理器。
 * <p>
 * 分配策略：
 * <ul>
 *   <li>System Prompt: ~500 tokens（固定，Prompt Caching 覆盖）</li>
 *   <li>检索文档: ~2000 tokens（超出截断至 Top-3）</li>
 *   <li>对话历史: ~2000 tokens（超出触发摘要压缩，每 6 轮压为 ~200 tokens）</li>
 *   <li>当前问题 + 回答预留: ~1500 tokens</li>
 * </ul>
 * 总预算：~6000 tokens（8K 窗口下留 25% 余量）
 */
public class TokenBudgetManager {

    private final int systemBudget;
    private final int docBudget;
    private final int historyBudget;
    private final int answerBudget;
    private final int compressionThreshold;

    public TokenBudgetManager(int systemBudget, int docBudget, int historyBudget, int answerBudget) {
        this.systemBudget = systemBudget;
        this.docBudget = docBudget;
        this.historyBudget = historyBudget;
        this.answerBudget = answerBudget;
        this.compressionThreshold = 6; // 每 6 轮触发摘要
    }

    public int totalBudget() {
        return systemBudget + docBudget + historyBudget + answerBudget;
    }

    public int docBudget() {
        return docBudget;
    }

    /**
     * 压缩对话历史。
     * 超出 historyBudget 时按滑动窗口截断最近的消息。
     * 每 compressionThreshold 轮触发摘要（Phase 2 实现 LLM 摘要）。
     */
    public String compressHistory(List<RagRequest.Message> history) {
        if (history.isEmpty()) return "";

        // 估算 token 数（粗略：1 token ≈ 2 字符）
        int totalEstTokens = history.stream()
                .mapToInt(m -> m.content().length() / 2)
                .sum();

        if (totalEstTokens <= historyBudget) {
            // 历史未超预算 → 完整保留
            return history.stream()
                    .map(m -> m.role() + ": " + m.content())
                    .collect(Collectors.joining("\n"));
        }

        // 超出预算 → 滑动窗口截断最近的消息
        int tokens = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = history.size() - 1; i >= 0; i--) {
            RagRequest.Message m = history.get(i);
            int msgTokens = m.content().length() / 2;
            if (tokens + msgTokens > historyBudget) {
                sb.insert(0, "...(更早的对话已省略)...\n");
                break;
            }
            sb.insert(0, m.role() + ": " + m.content() + "\n");
            tokens += msgTokens;
        }

        // 超长时触发摘要（Phase 2：用 LLM 压缩历史，而不是简单截断）
        if (history.size() >= compressionThreshold) {
            sb.insert(0, "[对话历史摘要] 这是之前 " + history.size() + " 轮对话的摘要...\n");
        }

        return sb.toString();
    }
}
