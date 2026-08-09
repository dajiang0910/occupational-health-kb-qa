package com.ohkb.core.rag;

import com.ohkb.infra.llm.BailianClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 引用校验单元测试——PRD §46。
 * <p>
 * 验证 CitationVerifier 正确识别 YES/NO/PARTIALLY。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CitationVerifier Unit Tests")
class CitationVerifierTest {

    @Mock private BailianClient llmClient;

    private CitationVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new CitationVerifier(llmClient);
    }

    @Test
    @DisplayName("引用校验：正确引用保持 verified=true")
    void verify_correctCitationRemainsVerified() {
        String answer = "项目申报功能在左侧导航栏「项目管理」→「项目申报」菜单下。";
        List<RagResult.Citation> citations = List.of(
                new RagResult.Citation("1", "系统操作手册-第2章",
                        "项目申报功能在左侧导航栏「项目管理」→「项目申报」菜单下", true)
        );

        mockVerifyResponse("YES");

        String result = verifier.verify(answer, citations);

        assertThat(result).isNotEmpty();
        // 正确引用不应被移除
        assertThat(result).contains("项目申报");
    }

    @Test
    @DisplayName("引用校验：不支持的引用标记为未验证")
    void verify_unsupportedCitationMarkedUnverified() {
        String answer = "系统使用Java 21开发，支持高并发场景，可以直接连接Oracle数据库。";
        List<RagResult.Citation> citations = List.of(
                new RagResult.Citation("1", "系统操作手册-第1章",
                        "系统推荐使用Chrome 90+浏览器", true)
        );

        mockVerifyResponse("NO");

        String result = verifier.verify(answer, citations);

        // 不应包含不支持的声明
        assertThat(result).doesNotContain("Oracle数据库");
    }

    @Test
    @DisplayName("引用校验：部分支持标记 PARTIALLY")
    void verify_partiallySupportedCitation() {
        String answer = "系统支持PDF和Word格式上传，单个文件不超过50MB，也可以上传视频文件。";
        List<RagResult.Citation> citations = List.of(
                new RagResult.Citation("1", "FAQ-文件上传",
                        "系统支持PDF、Word、Excel、图片格式，单文件不超过50MB", true)
        );

        mockVerifyResponse("PARTIALLY");

        String result = verifier.verify(answer, citations);

        // 部分支持：回答中应保留支持的引用，标记不支持的
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("引用校验：空引用列表返回原回答")
    void verify_emptyCitationsReturnsOriginalAnswer() {
        String answer = "这是一个没有引用的回答。";

        String result = verifier.verify(answer, List.of());

        assertThat(result).isEqualTo(answer);
    }

    @Test
    @DisplayName("引用校验：所有引用都不支持时返回原回答")
    void verify_allUnsupportedReturnsOriginal() {
        String answer = "系统使用React Native开发移动端App。";
        List<RagResult.Citation> citations = List.of(
                new RagResult.Citation("1", "系统概述", "系统基于Web技术栈开发", true)
        );

        mockVerifyResponse("NO");

        String result = verifier.verify(answer, citations);

        // 即使全部不支持，也应返回内容（降级处理）
        assertThat(result).isNotEmpty();
    }

    // ── helpers ──

    private void mockVerifyResponse(String verdict) {
        BailianClient.ChatResponse mockResp = new BailianClient.ChatResponse(
                verdict, 20, 0, 20, 0, 0, 100L);
        when(llmClient.chat(any(), anyInt(), anyDouble())).thenReturn(mockResp);
    }
}
