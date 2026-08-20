package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.dto.AgentReplyRequest;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ChatMessageDTO;
import com.qingyu.care.dto.SessionMessagesResponse;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ChatStreamSseMapper;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.ReadReceiptService;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatController 附件接线纯 Mockito 单测（I-2 终审发现：删掉这两处接线整条链仍编译运行、
 * 200 返回，只是附件静默丢失——getSessionMessages 正是 Step 2 存在的理由，agentReply
 * 是坐席发图的唯一开关）。
 *
 * <p>SecurityUtil/StpUtil 是静态调用，用 {@code mockStatic} 绕过真实登录态校验
 * （同 ConfigScopeResolverTest/TenantDataAspectTest 既有惯例）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock ChatService chatService;
    @Mock ChatFeedbackService chatFeedbackService;
    @Mock ChatStreamSseMapper sseMapper;
    @Mock ChatAttachmentService chatAttachmentService;
    @Mock ChatAttachmentRepository chatAttachmentRepository;
    @Mock FileService fileService;
    @Mock ReadReceiptService readReceiptService;
    @InjectMocks ChatController controller;

    private static ChatMessage message(Long id, MessageRole role, String content) {
        ChatMessage m = ChatMessage.builder().role(role).content(content).build();
        m.setId(id);
        return m;
    }

    private static ChatSession session(String sessionId, Long id) {
        ChatSession s = ChatSession.builder().sessionId(sessionId).build();
        s.setId(id);
        return s;
    }

    @Test
    void 历史消息按messageId分组装配附件() {
        when(chatService.getSession("s-1")).thenReturn(session("s-1", 100L));
        ChatMessage m1 = message(1L, MessageRole.USER, "看下这个报错");
        ChatMessage m2 = message(2L, MessageRole.ASSISTANT, "已收到");
        when(chatService.getSessionMessages("s-1")).thenReturn(List.of(m1, m2));
        ChatAttachment a = ChatAttachment.builder().fileName("a.png").contentType("image/png").build();
        a.setId(7L);
        a.setMessageId(1L);
        when(chatAttachmentRepository.findByMessageIdInOrderById(List.of(1L, 2L)))
                .thenReturn(List.of(a));
        when(readReceiptService.currentWatermark(100L, ReaderType.VISITOR, 100L)).thenReturn(2L);

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requireAgent).thenAnswer(inv -> null);

            ApiResponse<SessionMessagesResponse> resp = controller.getSessionMessages("s-1");

            assertThat(resp.getData().getMessages()).hasSize(2);
            ChatMessageDTO dto1 = resp.getData().getMessages().get(0);
            assertThat(dto1.getId()).isEqualTo(1L);
            assertThat(dto1.getAttachments()).hasSize(1);
            assertThat(dto1.getAttachments().get(0).getFileName()).isEqualTo("a.png");
            // 第 2 条消息没有附件——不能被第 1 条的附件"污染"
            ChatMessageDTO dto2 = resp.getData().getMessages().get(1);
            assertThat(dto2.getAttachments()).isEmpty();
            // 只带对方（访客）的水位线，不是坐席自己的
            assertThat(resp.getData().getVisitorReadUpToMessageId()).isEqualTo(2L);
        }
    }

    @Test
    void 历史消息为空时不查附件() {
        when(chatService.getSession("s-2")).thenReturn(session("s-2", 200L));
        when(chatService.getSessionMessages("s-2")).thenReturn(List.of());

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requireAgent).thenAnswer(inv -> null);

            ApiResponse<SessionMessagesResponse> resp = controller.getSessionMessages("s-2");

            assertThat(resp.getData().getMessages()).isEmpty();
            verifyNoInteractions(chatAttachmentRepository);
        }
    }

    @Test
    void agentReply透传附件id() {
        AgentReplyRequest req = AgentReplyRequest.builder()
                .sessionId("s-1").message("已处理").attachmentIds(List.of(3L, 4L)).build();

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class);
             MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            ms.when(SecurityUtil::requireAgent).thenAnswer(inv -> null);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(10L);

            controller.agentReply(req);

            verify(chatService).agentReply(eq("s-1"), eq("已处理"), eq(10L), eq(List.of(3L, 4L)));
        }
    }
}
