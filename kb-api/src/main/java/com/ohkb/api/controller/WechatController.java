package com.ohkb.api.controller;

import com.ohkb.core.rag.RagPipeline;
import com.ohkb.core.rag.RagRequest;
import com.ohkb.core.rag.RagResult;
import com.ohkb.core.ticket.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    // Phase 2: 注入 IntentDetector、GroupContextManager、RateLimiter

    public WechatController(RagPipeline ragPipeline, TicketService ticketService) {
        this.ragPipeline = ragPipeline;
        this.ticketService = ticketService;
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
        // Phase 1: 简化实现，直接返回 echostr
        // Phase 2: 完整签名校验 + AES 解密
        log.info("[WECHAT] URL verification request");
        return echostr;
    }

    /**
     * 接收企微消息推送（POST）。
     */
    @PostMapping("/callback")
    public Map<String, Object> receiveMessage(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String encryptedBody
    ) {
        log.info("[WECHAT] Message received: signature={}, bodyLength={}",
                msgSignature, encryptedBody.length());

        try {
            // Phase 1: 简化处理（Phase 2: 解密 + 消息解析 + 多信号意图识别）
            // 假设消息已解密，内容格式为 XML/JSON

            // 提取消息内容（简化）
            String userId = "wx-user-12345";
            String groupId = "wrABC123";
            String question = extractQuestion(encryptedBody);

            if (question == null || question.isBlank()) {
                return Map.of("errcode", 0, "errmsg", "ok"); // 非问题，不回复
            }

            // 调用 RAG Pipeline
            RagResult result = ragPipeline.answer(new RagRequest(
                    question, List.of(), Map.of(), null));

            // 构建企微回复
            String replyContent = result.answer();
            if (result.fallback()) {
                // 自动创建工单
                ticketService.createTicket(null, null, "wechat_group",
                        result.answer(), List.of());
                replyContent += "\n\n⚠️ 此问题已自动转接人工客服，请稍后。";
            }

            log.info("[WECHAT] Reply: {} chars, confidence={}, fallback={}",
                    replyContent.length(),
                    String.format("%.2f", result.confidence()),
                    result.fallback());

            return Map.of(
                    "errcode", 0,
                    "errmsg", "ok",
                    "reply", replyContent
            );

        } catch (Exception e) {
            log.error("[WECHAT] Message processing failed", e);
            return Map.of("errcode", 0, "errmsg", "ok"); // 返回成功防止企微重试
        }
    }

    /**
     * 群配置列表（占位）。
     */
    @GetMapping("/groups")
    public List<Map<String, String>> listGroups() {
        return List.of();
    }

    private String extractQuestion(String body) {
        // Phase 1: 简化实现
        // Phase 2: 解析 JSON/XML 格式的企微消息体
        if (body.contains("\"Content\"")) {
            // JSON 格式
            int start = body.indexOf("\"Content\":") + 11;
            int end = body.indexOf("\"", start);
            if (start > 10 && end > start) {
                return body.substring(start, end);
            }
        }
        return null;
    }
}
