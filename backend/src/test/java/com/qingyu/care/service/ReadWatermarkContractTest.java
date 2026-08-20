package com.qingyu.care.service;

import com.qingyu.care.controller.ChatController;
import com.qingyu.care.controller.open.OpenSessionController;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.SessionMessagesResponse;
import com.qingyu.care.dto.open.OpenChatResponse;
import com.qingyu.care.dto.open.OpenSessionResponse;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 契约测试：Task 7 补齐的三处响应字段——{@code OpenChatResponse.userMessageId}、
 * widget 侧历史响应的 {@code agentReadUpToMessageId}、坐席侧历史响应的
 * {@code visitorReadUpToMessageId}。
 *
 * <p>⚠️ 计划里第三条原本断言 {@code chatService.getVisitorHistory}/{@code getAgentHistory}
 * 两个占位方法名——本仓库不新增这两个 service 方法，而是直接给既有的两个历史查询入口
 * （{@link ChatController#getSessionMessages}、{@link OpenSessionController#getSession}）
 * 的响应加字段，故本测试改为直接驱动这两个控制器方法。
 *
 * <p>⚠️ 计划里第二条 {@code userMessageId无初始化器} 已删除：{@link OpenChatResponse} 是
 * {@code @Data + @Builder} 且<b>无</b> {@code @NoArgsConstructor}，{@code new OpenChatResponse()}
 * 编译不过；改用 builder 也测不出字段初始化器（Lombok {@code @Builder} 在没有
 * {@code @Builder.Default} 时会忽略初始化器）。「不要加初始化器」的理由已写进字段 Javadoc。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ReadWatermarkContractTest {

    @Mock ChatService chatService;
    @Mock ChatFeedbackService chatFeedbackService;
    @Mock ChatStreamSseMapper sseMapper;
    @Mock ChatAttachmentService chatAttachmentService;
    @Mock ChatAttachmentRepository chatAttachmentRepository;
    @Mock FileService fileService;
    @Mock ReadReceiptService readReceiptService;
    @Mock RealtimeHub realtimeHub;
    @Mock RealtimeNotifier realtimeNotifier;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("OpenChatResponse 的 userMessageId 与 messageId 是两个不同的字段")
    void 非流式响应带userMessageId() {
        // ⚠️ 现有 messageId 是 AI 回复的 id（供反馈按钮使用）；访客发出的那条自身的 id
        //    此前从未透出，导致 widget 无法与已读水位线比对。
        OpenChatResponse resp = OpenChatResponse.builder()
                .sessionId("s1")
                .messageId(200L)        // AI 回复
                .userMessageId(199L)    // 访客自己那条
                .reply("您好")
                .build();

        assertThat(resp.getUserMessageId()).isEqualTo(199L);
        assertThat(resp.getMessageId())
                .as("两者不能混用：混了就会把 AI 回复的 id 拿去跟访客侧水位线比对")
                .isNotEqualTo(resp.getUserMessageId());
    }

    @Test
    @DisplayName("访客侧历史（OpenSessionController.getSession）只带坐席的已读水位线")
    void 访客历史带坐席已读水位线() {
        ChatSession session = ChatSession.builder().sessionId("s-open").build();
        session.setId(10L);
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setAgentId(5L);
        ChatMessage m = ChatMessage.builder().role(MessageRole.USER).content("你好").build();
        m.setId(1L);
        when(chatService.getSession("s-open")).thenReturn(session);
        when(chatService.getSessionMessages("s-open")).thenReturn(List.of(m));
        when(chatAttachmentRepository.findByMessageIdInOrderById(List.of(1L))).thenReturn(List.of());
        // 坐席（对方）读到了第 42 条
        when(readReceiptService.currentWatermark(10L, ReaderType.AGENT, 5L)).thenReturn(42L);

        OpenSessionController controller = new OpenSessionController(
                chatService, realtimeHub, realtimeNotifier, objectMapper,
                chatAttachmentRepository, readReceiptService);

        OpenSessionResponse resp = controller.getSession("s-open");

        assertThat(resp.getAgentReadUpToMessageId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("访客历史：会话尚无坐席接管时不查水位线，直接为 null")
    void 访客历史_无坐席接管时水位线为null() {
        ChatSession session = ChatSession.builder().sessionId("s-open2").build();
        session.setId(11L);
        session.setStatus(SessionStatus.WAITING_AGENT);
        session.setAgentId(null);
        when(chatService.getSession("s-open2")).thenReturn(session);
        when(chatService.getSessionMessages("s-open2")).thenReturn(List.of());

        OpenSessionController controller = new OpenSessionController(
                chatService, realtimeHub, realtimeNotifier, objectMapper,
                chatAttachmentRepository, readReceiptService);

        OpenSessionResponse resp = controller.getSession("s-open2");

        assertThat(resp.getAgentReadUpToMessageId()).isNull();
        // 没有坐席，压根不该去查——不是「查了返回 null」
        org.mockito.Mockito.verifyNoInteractions(readReceiptService);
    }

    @Test
    @DisplayName("坐席侧历史（ChatController.getSessionMessages）只带访客的已读水位线")
    void 坐席历史带访客已读水位线() {
        ChatSession session = ChatSession.builder().sessionId("s-agent").build();
        session.setId(20L);
        ChatMessage m = ChatMessage.builder().role(MessageRole.ASSISTANT).content("在的").build();
        m.setId(2L);
        when(chatService.getSession("s-agent")).thenReturn(session);
        when(chatService.getSessionMessages("s-agent")).thenReturn(List.of(m));
        when(chatAttachmentRepository.findByMessageIdInOrderById(List.of(2L))).thenReturn(List.of());
        // 访客（对方）读到了第 88 条；readerId 按 §3.2.1 恒为 chat_session.id
        when(readReceiptService.currentWatermark(20L, ReaderType.VISITOR, 20L)).thenReturn(88L);

        ChatController controller = new ChatController(
                chatService, chatFeedbackService, sseMapper, chatAttachmentService,
                chatAttachmentRepository, fileService, readReceiptService);

        try (MockedStatic<SecurityUtil> ms = mockStatic(SecurityUtil.class)) {
            ms.when(SecurityUtil::requireAgent).thenAnswer(inv -> null);

            ApiResponse<SessionMessagesResponse> resp = controller.getSessionMessages("s-agent");

            assertThat(resp.getData().getVisitorReadUpToMessageId()).isEqualTo(88L);
            // 只带对方的：不能把坐席自己的水位线也混进来（同一水位线服务对 AGENT 类型压根没被调用）
            org.mockito.Mockito.verify(readReceiptService, org.mockito.Mockito.never())
                    .currentWatermark(org.mockito.ArgumentMatchers.anyLong(), org.mockito.Mockito.eq(ReaderType.AGENT),
                            org.mockito.ArgumentMatchers.anyLong());
        }
    }
}
