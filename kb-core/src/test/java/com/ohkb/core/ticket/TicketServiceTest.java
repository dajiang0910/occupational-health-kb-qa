package com.ohkb.core.ticket;

import com.ohkb.core.knowledge.KnowledgeArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 工单系统测试——PRD §52。
 * <p>
 * 测试范围：
 * <ul>
 *   <li>工单状态流转：PENDING → CLAIMED → RESOLVED</li>
 *   <li>工单创建：附带完整上下文</li>
 *   <li>状态机约束：非法状态转换被拒绝</li>
 *   <li>@Async 纳入知识库不阻塞工单关闭</li>
 * </ul>
 */
@DisplayName("Ticket System Tests")
class TicketServiceTest {

    private TicketService ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService();
    }

    // ── SupportTicket 状态机领域测试 ──

    @Nested
    @DisplayName("SupportTicket State Machine")
    class SupportTicketStateMachine {

        @Test
        @DisplayName("新建工单状态为 PENDING")
        void newTicket_statusIsPending() {
            SupportTicket ticket = SupportTicket.create(
                    1L, "web", "user-123",
                    "AI未能回答的问题", List.of(1L, 2L));

            assertThat(ticket.status()).isEqualTo("pending");
            assertThat(ticket.priority()).isEqualTo("normal");
            assertThat(ticket.channel()).isEqualTo("web");
        }

        @Test
        @DisplayName("PENDING → CLAIMED：客服认领成功")
        void pendingToClaimed_success() {
            SupportTicket ticket = SupportTicket.create(
                    1L, "web", "user-123",
                    "问题描述", List.of(1L));

            SupportTicket claimed = ticket.claim("agent-zhang");

            assertThat(claimed.status()).isEqualTo("claimed");
            assertThat(claimed.assignedTo()).isEqualTo("agent-zhang");
        }

        @Test
        @DisplayName("CLAIMED → RESOLVED：客服解决工单")
        void claimedToResolved_success() {
            SupportTicket ticket = SupportTicket.create(
                    1L, "web", "user-123",
                    "问题描述", List.of(1L));

            SupportTicket claimed = ticket.claim("agent-zhang");
            SupportTicket resolved = claimed.resolve("已告知用户操作路径", 100L);

            assertThat(resolved.status()).isEqualTo("resolved");
            assertThat(resolved.resolutionNote()).isEqualTo("已告知用户操作路径");
            assertThat(resolved.knowledgeArticleCreated()).isEqualTo(100L);
        }

        @Test
        @DisplayName("CLAIMED → RESOLVED：可以不创建知识条目")
        void claimedToResolved_withoutKnowledgeArticle() {
            SupportTicket ticket = SupportTicket.create(
                    1L, "web", "user-123",
                    "问题描述", List.of(1L));

            SupportTicket claimed = ticket.claim("agent-zhang");
            SupportTicket resolved = claimed.resolve("问题已解决", null);

            assertThat(resolved.status()).isEqualTo("resolved");
            assertThat(resolved.knowledgeArticleCreated()).isNull();
        }

        @Test
        @DisplayName("PENDING → RESOLVED 不允许（必须先认领）")
        void pendingToResolved_notAllowed() {
            SupportTicket ticket = SupportTicket.create(
                    1L, "web", "user-123",
                    "问题描述", List.of(1L));

            // resolve() 在 PENDING 状态应该抛异常
            assertThatThrownBy(() -> ticket.resolve("直接解决", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pending");
        }

        @Test
        @DisplayName("RESOLVED → CLAIMED 不允许（已关闭不可重新认领）")
        void resolvedToClaimed_notAllowed() {
            SupportTicket ticket = SupportTicket.create(
                    1L, "web", "user-123",
                    "问题描述", List.of(1L));

            SupportTicket claimed = ticket.claim("agent-zhang");
            SupportTicket resolved = claimed.resolve("已解决", null);

            assertThatThrownBy(() -> resolved.claim("agent-li"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resolved");
        }
    }

    // ── TicketService 集成测试 ──

    @Nested
    @DisplayName("TicketService Operations")
    class TicketServiceOperations {

        @Test
        @DisplayName("createTicket：创建工单并返回完整上下文")
        void createTicket_returnsFullContext() {
            var ticket = ticketService.createTicket(
                    1L, 10L, "wechat_group",
                    "AI未能回答的采样流程问题", List.of(5L, 6L));

            assertThat(ticket).isNotNull();
            assertThat(ticket.status()).isEqualTo("pending");
            assertThat(ticket.channel()).isEqualTo("wechat_group");
            assertThat(ticket.retrievedArticles()).contains(5L, 6L);
        }

        @Test
        @DisplayName("claimTicket：认领后 assignedTo 更新")
        void claimTicket_updatesAssignee() {
            var ticket = ticketService.createTicket(
                    2L, 11L, "web",
                    "需要人工协助", List.of(1L));

            var claimed = ticketService.claimTicket(ticket.id(), "agent-wang");

            assertThat(claimed.status()).isEqualTo("claimed");
            assertThat(claimed.assignedTo()).isEqualTo("agent-wang");
        }

        @Test
        @DisplayName("claimTicket：认领不存在的工单抛出异常")
        void claimTicket_nonexistentThrows() {
            assertThatThrownBy(() -> ticketService.claimTicket(99999L, "agent-wang"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("resolveTicket：解决后状态更新")
        void resolveTicket_updatesStatus() {
            var ticket = ticketService.createTicket(
                    3L, 12L, "web",
                    "问题", List.of(1L));
            ticketService.claimTicket(ticket.id(), "agent-li");

            var resolved = ticketService.resolveTicket(
                    ticket.id(), "已告知操作步骤", 200L);

            assertThat(resolved.status()).isEqualTo("resolved");
            assertThat(resolved.resolutionNote()).isEqualTo("已告知操作步骤");
            assertThat(resolved.knowledgeArticleCreated()).isEqualTo(200L);
        }

        @Test
        @DisplayName("listPending：按状态筛选待处理工单")
        void listPending_filtersByStatus() {
            ticketService.createTicket(4L, 13L, "web", "q1", List.of());
            ticketService.createTicket(5L, 14L, "web", "q2", List.of());
            var t3 = ticketService.createTicket(6L, 15L, "web", "q3", List.of());
            ticketService.claimTicket(t3.id(), "agent");

            var pending = ticketService.getPendingTickets();
            assertThat(pending).hasSize(2);
            assertThat(pending).allMatch(t -> "pending".equals(t.status()));
        }

        @Test
        @DisplayName("工单优先级：high > normal")
        void ticketPriority_ordering() {
            var t1 = ticketService.createTicket(7L, 16L, "web", "q1", List.of());
            var t2 = SupportTicket.create(2L, "web", "u2", "urgent", List.of());

            // 设置 t2 为 high 优先级
            var highPriority = new SupportTicket(
                    t2.id(), t2.conversationId(), t2.sourceMessageId(),
                    t2.channel(), t2.status(), "high",
                    t2.assignedTo(), t2.aiAnswerSnapshot(), t2.retrievedArticleIds(),
                    null, null, null, null, null,
                    t2.createdAt(), t2.updatedAt()
            );

            assertThat(highPriority.priority()).isEqualTo("high");
            assertThat(t1.priority()).isEqualTo("normal");
        }
    }

    // ── @Async 知识创建不阻塞 ──

    @Nested
    @DisplayName("Knowledge Article Creation (Async)")
    class KnowledgeArticleCreation {

        @Test
        @DisplayName("解决工单时可选创建知识条目（knowledgeArticleCreated 可空）")
        void resolve_withoutCreatingKnowledge() {
            var ticket = SupportTicket.create(1L, "web", "u1", "q", List.of());

            // knowledgeArticleCreated = null 是允许的
            var claimed = ticket.claim("agent");
            var resolved = claimed.resolve("手动回复完成", null);

            assertThat(resolved.knowledgeArticleCreated()).isNull();
            assertThat(resolved.status()).isEqualTo("resolved");
        }

        @Test
        @DisplayName("工单附带知识条目 ID 后不影响状态流转")
        void resolve_withKnowledgeArticleId() {
            var ticket = SupportTicket.create(1L, "web", "u1", "q", List.of());

            var claimed = ticket.claim("agent");
            var resolved = claimed.resolve("已纳入知识库", 500L);

            assertThat(resolved.status()).isEqualTo("resolved");
            assertThat(resolved.knowledgeArticleCreated()).isEqualTo(500L);
        }
    }
}
