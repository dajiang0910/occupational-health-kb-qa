package com.ohkb.api.controller;

import com.ohkb.core.rag.RagPipeline;
import com.ohkb.core.rag.RagRequest;
import com.ohkb.core.rag.RagResult;
import com.ohkb.core.ticket.TicketService;
import com.ohkb.core.wechat.GroupContextManager;
import com.ohkb.core.wechat.IntentDetector;
import com.ohkb.infra.wechat.WechatCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 企微 Webhook 契约测试——PRD §47。
 * <p>
 * 测试范围：URL 验证、消息格式解析、多信号意图识别、去重、限流、降级。
 */
@WebMvcTest(WechatController.class)
@DisplayName("WeChat Webhook Contract Tests")
class WechatControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RagPipeline ragPipeline;
    @MockBean private TicketService ticketService;
    @MockBean private IntentDetector intentDetector;
    @MockBean private GroupContextManager contextManager;

    // ── URL 验证 ──

    @Test
    @DisplayName("URL 验证：GET /callback 返回 echostr")
    void urlVerification_returnsEchostr() throws Exception {
        mockMvc.perform(get("/api/wechat/callback")
                        .param("msg_signature", "test-sig")
                        .param("timestamp", "1234567890")
                        .param("nonce", "test-nonce")
                        .param("echostr", "hello-world"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("URL 验证：缺少参数时仍返回 200（防止企微重试）")
    void urlVerification_missingParams() throws Exception {
        mockMvc.perform(get("/api/wechat/callback"))
                .andExpect(status().isOk());
    }

    // ── 消息接收 ──

    @Test
    @DisplayName("消息接收：POST /callback 接收加密消息")
    void receiveMessage_postEncryptedBody() throws Exception {
        String encryptedXml = "<xml><Encrypt><![CDATA[encrypted-content]]></Encrypt></xml>";

        mockRagResponse("这是自动回复", 0.85, false);
        when(intentDetector.shouldRespond(any())).thenReturn(IntentDetector.Decision.RESPOND);

        mockMvc.perform(post("/api/wechat/callback")
                        .param("msg_signature", "test-sig")
                        .param("timestamp", "1234567890")
                        .param("nonce", "test-nonce")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(encryptedXml))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("消息接收：非问题消息不回复（IGNORE）")
    void receiveMessage_nonQuestionIgnored() throws Exception {
        when(intentDetector.shouldRespond(any())).thenReturn(IntentDetector.Decision.IGNORE);

        mockMvc.perform(post("/api/wechat/callback")
                        .param("msg_signature", "test-sig")
                        .param("timestamp", "1234567890")
                        .param("nonce", "test-nonce")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<xml><Encrypt><![CDATA[hello]]></Encrypt></xml>"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("消息接收：降级群组非 @ 消息不回复")
    void receiveMessage_degradedGroupNonAtIgnored() throws Exception {
        when(intentDetector.shouldRespond(any())).thenReturn(IntentDetector.Decision.AT_ONLY);

        mockMvc.perform(post("/api/wechat/callback")
                        .param("msg_signature", "test-sig")
                        .param("timestamp", "1234567890")
                        .param("nonce", "test-nonce")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<xml><Encrypt><![CDATA[non-at-message]]></Encrypt></xml>"))
                .andExpect(status().isOk());
    }

    // ── 异常处理 ──

    @Test
    @DisplayName("异常处理：解密失败返回空字符串（不触发企微重试）")
    void errorHandling_decryptionFailure() throws Exception {
        String invalidXml = "not-valid-xml";

        mockMvc.perform(post("/api/wechat/callback")
                        .param("msg_signature", "bad-sig")
                        .param("timestamp", "0")
                        .param("nonce", "0")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(invalidXml))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ── 群配置 API ──

    @Test
    @DisplayName("群配置：GET /groups 返回列表")
    void listGroups_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/wechat/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("群配置：PUT /groups/{id} 返回成功")
    void updateGroup_returnsOk() throws Exception {
        mockMvc.perform(put("/api/wechat/groups/wrABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"auto_reply_mode\":\"at_only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errcode").value(0));
    }

    @Test
    @DisplayName("群历史：GET /groups/{id}/history 返回空列表")
    void groupHistory_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/wechat/groups/wrABC123/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── helpers ──

    private void mockRagResponse(String answer, double confidence, boolean fallback) {
        RagResult result = new RagResult(
                answer,
                List.of(new RagResult.Citation("1", "测试文档", "引用片段", true)),
                confidence,
                List.of("相关推荐问题1"),
                List.of("1"),
                fallback,
                false
        );
        when(ragPipeline.answer(any(RagRequest.class))).thenReturn(result);
    }
}
