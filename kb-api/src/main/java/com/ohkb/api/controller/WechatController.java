package com.ohkb.api.controller;

import com.ohkb.core.rag.RagPipeline;
import com.ohkb.core.rag.RagRequest;
import com.ohkb.core.rag.RagResult;
import com.ohkb.core.ticket.TicketService;
import com.ohkb.core.wechat.GroupContextManager;
import com.ohkb.core.wechat.IntentDetector;
import com.ohkb.infra.wechat.WechatCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * 企业微信 Webhook 回调 Controller。
 * <p>
 * 处理企微群消息推送：GET（URL 验证）+ POST（消息接收）。
 */
@RestController
@RequestMapping("/api/wechat")
public class WechatController {

    private static final Logger log = LoggerFactory.getLogger(WechatController.class);

    private final RagPipeline ragPipeline;
    private final TicketService ticketService;
    private final WechatCrypto wechatCrypto;
    private final IntentDetector intentDetector;
    private final GroupContextManager contextManager;

    public WechatController(
            RagPipeline ragPipeline,
            TicketService ticketService,
            IntentDetector intentDetector,
            GroupContextManager contextManager,
            @Value("${wechat.token}") String token,
            @Value("${wechat.encoding-aes-key}") String encodingAesKey,
            @Value("${wechat.corp-id}") String corpId
    ) {
        this.ragPipeline = ragPipeline;
        this.ticketService = ticketService;
        this.intentDetector = intentDetector;
        this.contextManager = contextManager;
        this.wechatCrypto = new WechatCrypto(token, encodingAesKey, corpId);
    }

    /**
     * 企微回调 URL 验证（GET）。
     */
    @GetMapping("/callback")
    public String verifyUrl(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr
    ) {
        log.info("[WECHAT] URL verification request");
        try {
            return wechatCrypto.verifyUrl(msgSignature, timestamp, nonce, echostr);
        } catch (Exception e) {
            log.error("[WECHAT] URL verification failed", e);
            return "verification failed";
        }
    }

    /**
     * 接收企微消息推送（POST）。
     */
    @PostMapping("/callback")
    public String receiveMessage(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String encryptedBody
    ) {
        log.info("[WECHAT] Message received: signature={}, bodyLength={}",
                msgSignature, encryptedBody.length());

        try {
            // 1. 解密消息
            String decryptedXml = wechatCrypto.decryptMessage(msgSignature, timestamp, nonce, encryptedBody);

            // 2. 解析消息字段
            WechatMessage msg = parseWechatMessage(decryptedXml);
            if (msg == null) {
                return ""; // 非文本消息或不支持的类型，不回复
            }

            log.info("[WECHAT] Parsed: fromUser={}, groupId={}, msgType={}, content={}",
                    msg.fromUser(), msg.groupId(), msg.msgType(), msg.content());

            // 3. 去重检查（MsgId 幂等）
            if (isDuplicate(msg.msgId())) {
                log.debug("[WECHAT] Duplicate message ignored: msgId={}", msg.msgId());
                return "";
            }

            // 4. 意图检测——多信号融合
            GroupContextManager.UserThread thread = contextManager.getActiveThread(
                    msg.groupId(), msg.fromUser());
            boolean isAtBot = msg.content() != null && msg.content().contains("@AI助手");

            IntentDetector.Context intentCtx = new IntentDetector.Context(
                    msg.groupId(), msg.fromUser(), msg.content(), msg.msgId(),
                    msg.replyToMsgId(), Instant.now(), isAtBot, thread
            );

            IntentDetector.Decision decision = intentDetector.shouldRespond(intentCtx);
            if (decision == IntentDetector.Decision.IGNORE) {
                log.debug("[WECHAT] Intent: IGNORE (not a question)");
                return "";
            }
            if (decision == IntentDetector.Decision.AT_ONLY && !isAtBot) {
                log.debug("[WECHAT] Intent: AT_ONLY (group degraded, not @mentioned)");
                return "";
            }

            // 5. 解析用户线程（获取对话历史）
            GroupContextManager.UserThread resolvedThread = contextManager.resolveThread(
                    msg.groupId(), msg.fromUser(), msg.replyToMsgId());
            List<Map<String, String>> recentMessages = resolvedThread.recentMessages(6);

            // 6. 调用 RAG Pipeline
            RagResult result = ragPipeline.answer(new RagRequest(
                    msg.content(), List.of(), Map.of(), null));

            // 7. 记录到用户线程
            contextManager.addMessage(msg.groupId(), msg.fromUser(), "user", msg.content());
            contextManager.addMessage(msg.groupId(), msg.fromUser(), "assistant", result.answer());

            // 8. 构建回复
            String replyContent = result.answer();
            if (result.fallback()) {
                ticketService.createTicket(
                        msg.fromUser(), msg.groupId(), "wechat_group",
                        result.answer(), List.of());
                replyContent += "\n\n⚠️ 此问题已自动转接人工客服，请稍后。";
                intentDetector.recordMistrigger(msg.groupId());
            } else {
                intentDetector.recordNormalInteraction(msg.groupId());
            }

            log.info("[WECHAT] Reply: {} chars, confidence={}, fallback={}",
                    replyContent.length(),
                    String.format("%.2f", result.confidence()),
                    result.fallback());

            // 9. 加密回复
            String replyXml = buildReplyXml(msg.fromUser(), msg.groupId(), replyContent);
            String encryptedReply = wechatCrypto.encryptMessage(replyXml, timestamp, nonce);

            // 10. 记录 Bot 回复（用于回复链检测）
            intentDetector.recordBotReply(msg.msgId());

            return encryptedReply;

        } catch (WechatCrypto.WechatCryptoException e) {
            log.error("[WECHAT] Crypto error", e);
            return ""; // 签名/解密失败，不回复
        } catch (Exception e) {
            log.error("[WECHAT] Message processing failed", e);
            return ""; // 返回空字符串不触发企微重试
        }
    }

    /**
     * 群配置列表（占位）。
     */
    @GetMapping("/groups")
    public List<Map<String, String>> listGroups() {
        return List.of();
    }

    /**
     * 群配置更新（占位）。
     */
    @PutMapping("/groups/{id}")
    public Map<String, Object> updateGroup(@PathVariable String id,
                                           @RequestBody Map<String, Object> config) {
        log.info("[WECHAT] Group config update: id={}, config={}", id, config);
        return Map.of("errcode", 0, "errmsg", "ok");
    }

    /**
     * 群对话历史（占位）。
     */
    @GetMapping("/groups/{id}/history")
    public List<Map<String, String>> groupHistory(@PathVariable String id) {
        return List.of();
    }

    // ── 消息解析 ──

    /**
     * 解析企微 XML 消息体。
     */
    private WechatMessage parseWechatMessage(String xml) {
        try {
            String msgType = extractXmlField(xml, "MsgType");
            if (!"text".equals(msgType)) {
                log.debug("[WECHAT] Non-text message ignored: type={}", msgType);
                return null;
            }

            return new WechatMessage(
                    extractXmlField(xml, "FromUserName"),
                    extractXmlField(xml, "ToUserName"),  // 群 ID
                    msgType,
                    extractXmlField(xml, "Content"),
                    extractXmlField(xml, "MsgId"),
                    extractXmlField(xml, "ReplyToMsgId"),  // 回复的消息 ID
                    Long.parseLong(extractXmlField(xml, "CreateTime") != null
                            ? extractXmlField(xml, "CreateTime") : "0")
            );
        } catch (Exception e) {
            log.warn("[WECHAT] Failed to parse message XML", e);
            return null;
        }
    }

    private String extractXmlField(String xml, String tagName) {
        int start = xml.indexOf("<" + tagName + ">");
        int end = xml.indexOf("</" + tagName + ">");
        if (start < 0 || end < 0) {
            // Try CDATA variant
            start = xml.indexOf("<" + tagName + "><![CDATA[");
            if (start < 0) {
                start = xml.indexOf("<" + tagName + ">");
                end = xml.indexOf("</" + tagName + ">");
                if (start < 0 || end < 0) return null;
                return xml.substring(start + tagName.length() + 2, end).trim();
            }
            end = xml.indexOf("]]></" + tagName + ">");
            if (end < 0) return null;
            return xml.substring(start + tagName.length() + 11, end);
        }
        return xml.substring(start + tagName.length() + 2, end).trim();
    }

    private String buildReplyXml(String toUser, String groupId, String content) {
        return String.format(
                "<xml>" +
                "<ToUserName><![CDATA[%s]]></ToUserName>" +
                "<FromUserName><![CDATA[%s]]></FromUserName>" +
                "<CreateTime>%d</CreateTime>" +
                "<MsgType><![CDATA[text]]></MsgType>" +
                "<Content><![CDATA[%s]]></Content>" +
                "</xml>",
                toUser, groupId, System.currentTimeMillis() / 1000, content
        );
    }

    // ── 去重 ──

    private final Set<String> recentMsgIds = new LinkedHashSet<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 1000;
        }
    };

    private boolean isDuplicate(String msgId) {
        if (msgId == null) return false;
        return !recentMsgIds.add(msgId);
    }

    // ── 内部类型 ──

    private record WechatMessage(
            String fromUser,
            String groupId,
            String msgType,
            String content,
            String msgId,
            String replyToMsgId,
            long createTime
    ) {}
}
