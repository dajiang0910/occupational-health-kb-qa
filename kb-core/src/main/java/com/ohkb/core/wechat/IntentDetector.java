package com.ohkb.core.wechat;

import com.ohkb.infra.llm.BailianClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企微群消息意图检测——多信号融合。
 * <p>
 * 三个信号 OR 逻辑，任一高置信命中即触发应答。
 * 连续误触发 → 自动降级为"仅 @ 回复模式"。
 *
 * <pre>
 * 信号 1（回复链）：reply_to 指向非 Bot → 绝对不回应
 * 信号 2（用户窗口）：用户 5 分钟内刚与 Bot 交互过 → 窗口内消息高概率命中
 * 信号 3（模型二分类）：前两个信号不明确时，走 LLM 二分类，阈值 0.85+
 * </pre>
 */
@Component
public class IntentDetector {

    private static final Logger log = LoggerFactory.getLogger(IntentDetector.class);

    private static final String CLASSIFY_PROMPT = """
            你是一个群聊消息分析器。判断以下消息是否是在向 AI 助手提问或寻求帮助。

            分析维度：
            1. 消息是否包含明确的问题？
            2. 消息是否与系统操作、功能使用相关？
            3. 消息是否包含 @提及 或明确的求助语义？

            仅回复 YES 或 NO。
            """;

    private final BailianClient llmClient;
    private final int userWindowMinutes;
    private final int mistriggerWindowMinutes;
    private final int maxConsecutiveMistriggers;

    // 群级降级状态：groupId → DegradeState
    private final ConcurrentHashMap<String, GroupDegradeState> degradeStates = new ConcurrentHashMap<>();

    // Bot 已回复的消息 ID 集合（用于检测回复链）
    private final ConcurrentHashMap<String, String> botReplyMsgIds = new ConcurrentHashMap<>();

    public IntentDetector(
            BailianClient llmClient,
            @Value("${app.wechat.user-window-minutes:5}") int userWindowMinutes,
            @Value("${app.wechat.degrade.mistrigger-window-minutes:10}") int mistriggerWindowMinutes,
            @Value("${app.wechat.degrade.max-consecutive-mistriggers:3}") int maxConsecutiveMistriggers
    ) {
        this.llmClient = llmClient;
        this.userWindowMinutes = userWindowMinutes;
        this.mistriggerWindowMinutes = mistriggerWindowMinutes;
        this.maxConsecutiveMistriggers = maxConsecutiveMistriggers;
    }

    /**
     * 检测结果。
     */
    public enum Decision {
        RESPOND,      // 应该回复
        IGNORE,       // 不应该回复
        AT_ONLY       // 该群已降级，仅 @ 回复
    }

    /**
     * 检测上下文。
     */
    public record Context(
            String groupId,
            String userId,
            String content,
            String msgId,
            String replyToMsgId,  // 回复的消息 ID（可为 null）
            Instant timestamp,
            boolean isAtBot,       // 是否 @了 Bot
            GroupContextManager.UserThread activeThread  // 用户当前活跃线程（可为 null）
    ) {}

    /**
     * 判断是否应该回复此消息。
     */
    public Decision shouldRespond(Context ctx) {
        // ── 降级检查 ──
        GroupDegradeState state = degradeStates.get(ctx.groupId());
        if (state != null && state.isDegraded()) {
            if (ctx.isAtBot()) {
                return Decision.RESPOND; // @ 回复仍然响应
            }
            return Decision.AT_ONLY;
        }

        // ── 信号 1：回复链检测 ──
        if (ctx.replyToMsgId() != null && !ctx.replyToMsgId().isEmpty()) {
            // 回复了 Bot 的消息 → 高置信应答
            if (botReplyMsgIds.containsKey(ctx.replyToMsgId())) {
                log.info("[INTENT] Signal-1 (reply-to-bot): group={}, user={}", ctx.groupId(), ctx.userId());
                return Decision.RESPOND;
            }
            // 回复了其他人的消息 → 绝对不回应
            log.debug("[INTENT] Signal-1 (reply-to-other): group={}, user={}", ctx.groupId(), ctx.userId());
            return Decision.IGNORE;
        }

        // ── 信号 2：用户时间窗口 ──
        if (ctx.activeThread() != null && ctx.activeThread().isActive(userWindowMinutes)) {
            log.info("[INTENT] Signal-2 (user-window): group={}, user={}", ctx.groupId(), ctx.userId());
            return Decision.RESPOND;
        }

        // ── 信号 3：@Bot 检测 ──
        if (ctx.isAtBot()) {
            log.info("[INTENT] Signal-3 (at-bot): group={}, user={}", ctx.groupId(), ctx.userId());
            return Decision.RESPOND;
        }

        // ── 信号 4：LLM 二分类（信号不明确时）──
        return classifyLlm(ctx);
    }

    /**
     * LLM 二分类判断。
     */
    private Decision classifyLlm(Context ctx) {
        try {
            String prompt = CLASSIFY_PROMPT + "\n\n消息内容: " + ctx.content();

            BailianClient.ChatResponse resp = llmClient.chat(
                    List.of(BailianClient.userMessage(prompt)),
                    10, 0.0
            );

            boolean isQuestion = resp.content().trim().toUpperCase().contains("YES");
            if (isQuestion) {
                log.info("[INTENT] Signal-4 (LLM-yes): group={}, user={}", ctx.groupId(), ctx.userId());
                return Decision.RESPOND;
            } else {
                log.debug("[INTENT] Signal-4 (LLM-no): group={}, user={}", ctx.groupId(), ctx.userId());
                return Decision.IGNORE;
            }

        } catch (Exception e) {
            log.warn("[INTENT] LLM classification failed, defaulting to IGNORE", e);
            return Decision.IGNORE; // 宁可漏答不可误答
        }
    }

    /**
     * 记录 Bot 发送的回复（用于回复链检测）。
     */
    public void recordBotReply(String msgId) {
        botReplyMsgIds.put(msgId, "");
        // 限制大小
        if (botReplyMsgIds.size() > 10000) {
            botReplyMsgIds.clear();
        }
    }

    /**
     * 记录误触发（用户没有继续交互）。
     */
    public void recordMistrigger(String groupId) {
        GroupDegradeState state = degradeStates.computeIfAbsent(groupId,
                k -> new GroupDegradeState());
        int count = state.recordMistrigger(mistriggerWindowMinutes);

        if (count >= maxConsecutiveMistriggers) {
            state.degrade();
            log.warn("[INTENT] Group {} degraded to AT_ONLY mode after {} mistriggers",
                    groupId, count);
        }
    }

    /**
     * 记录正常交互（重置误触发计数）。
     */
    public void recordNormalInteraction(String groupId) {
        GroupDegradeState state = degradeStates.get(groupId);
        if (state != null) {
            state.reset();
        }
    }

    /**
     * 手动恢复群为正常模式。
     */
    public void recoverGroup(String groupId) {
        degradeStates.remove(groupId);
        log.info("[INTENT] Group {} manually recovered to normal mode", groupId);
    }

    /**
     * 获取群降级状态。
     */
    public boolean isDegraded(String groupId) {
        GroupDegradeState state = degradeStates.get(groupId);
        return state != null && state.isDegraded();
    }

    // ── 内部类型 ──

    private static class GroupDegradeState {
        private volatile boolean degraded;
        private Instant degradedAt;
        private AtomicInteger consecutiveMistriggers = new AtomicInteger(0);
        private Instant lastMistriggerTime;

        synchronized int recordMistrigger(int windowMinutes) {
            Instant now = Instant.now();
            if (lastMistriggerTime != null &&
                    now.minusSeconds(windowMinutes * 60L).isAfter(lastMistriggerTime)) {
                // 窗口过期，重置
                consecutiveMistriggers.set(0);
            }
            lastMistriggerTime = now;
            return consecutiveMistriggers.incrementAndGet();
        }

        synchronized void degrade() {
            degraded = true;
            degradedAt = Instant.now();
        }

        synchronized void reset() {
            consecutiveMistriggers.set(0);
            lastMistriggerTime = null;
        }

        boolean isDegraded() {
            return degraded;
        }

        Instant degradedAt() {
            return degradedAt;
        }
    }
}
