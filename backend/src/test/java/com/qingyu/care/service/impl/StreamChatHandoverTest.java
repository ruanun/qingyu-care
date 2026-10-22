package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.embedding.EmbeddingModelResolver;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.qingyu.care.service.embedding.EmbeddingModelRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * streamChat 与 chat() 对齐单测：验证接管态 / 关键词转人工下 streamChat 不调用 AI、
 * 返回与 chat() 一致的固定静默文案，并把用户消息 / 新待接事件实时推给坐席（复用 chat() 抽取的
 * isAgentControlled / triggerKeywordHandoverIfHit / persistUserMessageDuringHandover 三个私有方法）。
 * 另补一个 chat() 的等价静默用例，确认抽取未破坏同步路径的既有行为。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatHandoverTest {

    @Mock EmbeddingModelRegistry embeddingModelRegistry;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AbTestService abTestService;
    @Mock ConfigService configService;
    @Mock ChatToolService chatToolService;
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelResolver embeddingModelResolver;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @Mock ChatAttachmentService chatAttachmentService;
    @InjectMocks ChatServiceImpl chatService;

    @BeforeEach
    void wireSelf() {
        // chat()/streamChat() 经 self.searchKnowledge 路由；单测无 Spring，self 需手动指向本实例防 NPE
        chatService.setSelf(chatService);
        // chat()/streamChat() 现要求非空租户上下文（见 ChatServiceImpl#requireTenantContext）。
        // 必须与本类夹具会话的 setTenantId(9L) 一致：belongsToCurrentContext 仅在
        // 「上下文与实体都非空且不等」时拒绝，取 9L 可保归属判定结果与加守卫前完全相同
        // （若取别的值会被判为他租户会话 → 走新建分支，静默改变各用例的被测路径）。
        TenantContext.setTenantId(9L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void streamChat_人工接管态_静默不调AI_推用户消息给坐席() {
        ChatSession session = ChatSession.builder().sessionId("s1").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // TenantContext 归属放行（无上下文即放行，见 belongsToCurrentContext）

        List<ChatStreamEvent> events = chatService.streamChat(
                ChatRequest.builder().sessionId("s1").message("你好").build()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(ChatStreamEvent.Type.START);
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).sessionId()).isEqualTo("s1");
        // M-1：人工接管态 suggestHandover 恒 false——会话已在人工流程中，再提示是噪声
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).suggestHandover()).isFalse();
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text())
                .isEqualTo("[会话已转接人工客服，请等待回复]");
        assertThat(events.get(2).type()).isEqualTo(ChatStreamEvent.Type.DONE);
        verify(modelRouterService, never()).streamWithFallback(any(), any());
        verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());
    }

    @Test
    void streamChat_等待接管态_不推坐席_仅静默() {
        // WAITING_AGENT 尚未被坐席接管（agentId 为空），只静默存消息，不推 toAgentMessage
        ChatSession session = ChatSession.builder().sessionId("s0").build();
        session.setStatus(SessionStatus.WAITING_AGENT);
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId("s0")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        List<ChatStreamEvent> events = chatService.streamChat(
                ChatRequest.builder().sessionId("s0").message("你好").build()).collectList().block();

        assertThat(events).hasSize(3);
        // M-1：等待接管态同样是人工流程，suggestHandover 恒 false
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).suggestHandover()).isFalse();
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text())
                .isEqualTo("[会话已转接人工客服，请等待回复]");
        verify(modelRouterService, never()).streamWithFallback(any(), any());
        verify(realtimeNotifier, never()).toAgentMessage(any(), any(), any());
    }

    @Test
    void streamChat_命中关键词转人工_不调AI_广播新待接() {
        ChatSession session = ChatSession.builder().sessionId("s2").build();
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(handoverRuleService.checkKeywordTrigger("人工")).thenReturn(true);

        List<ChatStreamEvent> events = chatService.streamChat(
                ChatRequest.builder().sessionId("s2").message("人工").build()).collectList().block();

        assertThat(events).hasSize(3);
        // M-1：关键词触发转人工与 chat() 同语义，suggestHandover 恒 true，否则管理端提示条不出现
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).suggestHandover()).isTrue();
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text())
                .isEqualTo("正在为您转接人工客服，请稍候...");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.WAITING_AGENT);
        verify(modelRouterService, never()).streamWithFallback(any(), any());
        verify(realtimeNotifier).newWaitingToTenantAgents(eq(9L), eq(session));
    }

    @Test
    void streamChat_已接管态_发含关键词消息_仍静默不重复触发转人工() {
        // 会话已是 AGENT_PROCESSING（正被坐席接管），此时用户发含转人工关键词的消息，
        // streamChat 应先判人工接管态静默（与 chat() 同序），而不是被关键词打回 WAITING_AGENT
        ChatSession session = ChatSession.builder().sessionId("s2").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // 正确顺序下已接管态短路、不会再走到关键词判断，此桩仅用于证明"若顺序颠倒会误判"，标 lenient 防误报
        lenient().when(handoverRuleService.checkKeywordTrigger("我要转人工")).thenReturn(true);

        List<ChatStreamEvent> events = chatService.streamChat(
                ChatRequest.builder().sessionId("s2").message("我要转人工").build()).collectList().block();

        assertThat(events).hasSize(3);
        // M-1：已接管态短路优先，即便消息含关键词也不当作关键词触发，suggestHandover 仍为 false
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).suggestHandover()).isFalse();
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text())
                .isEqualTo("[会话已转接人工客服，请等待回复]");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.AGENT_PROCESSING);   // 未被打回 WAITING_AGENT
        verify(eventPublisher, never()).publishEvent(any());                          // 不重复发 handover 事件
        verify(realtimeNotifier, never()).newWaitingToTenantAgents(anyLong(), any()); // 不广播新待接
        verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());               // 静默转发给接管坐席
    }

    @Test
    void chat_人工接管态_静默不调AI_推用户消息给坐席_抽取未破坏同步路径() {
        ChatSession session = ChatSession.builder().sessionId("s3").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s3")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatResponse resp = chatService.chat(
                ChatRequest.builder().sessionId("s3").message("你好").build());

        assertThat(resp.getContent()).isEqualTo("[会话已转接人工客服，请等待回复]");
        assertThat(resp.isSuggestHandover()).isFalse();
        verify(modelRouterService, never()).callWithFallback(any(), any());
        verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());
    }

    /**
     * MM-6：人工接管态静默存消息的分支（persistUserMessageDuringHandover）也要接线附件，
     * 否则用户在等待接管期间发的图会静默变孤儿。
     */
    @Test
    void streamChat_人工接管态_带附件时关联到用户消息() {
        ChatSession session = ChatSession.builder().sessionId("s1").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            m.setId(55L);
            return m;
        });

        List<ChatStreamEvent> events = chatService.streamChat(ChatRequest.builder().sessionId("s1").message("图在这")
                .attachmentIds(List.of(9L)).build()).collectList().block();

        verify(chatAttachmentService).attachToMessage(eq(List.of(9L)), eq("s1"), eq(55L));
        // 契约补齐（Task 7）：人工接管静默分支的用户消息在构造 START 前已经落库，START 携带真实 id
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).userMessageId()).isEqualTo(55L);
    }

    /**
     * MM-6：关键词触发转人工的分支（triggerKeywordHandoverIfHit）也要接线附件——用户很可能就是
     * 在附截图的同时打出"转人工"这类关键词。
     */
    @Test
    void streamChat_命中关键词转人工_带附件时关联到用户消息() {
        ChatSession session = ChatSession.builder().sessionId("s2").build();
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            m.setId(66L);
            return m;
        });
        when(handoverRuleService.checkKeywordTrigger("人工")).thenReturn(true);

        List<ChatStreamEvent> events = chatService.streamChat(ChatRequest.builder().sessionId("s2").message("人工")
                .attachmentIds(List.of(11L)).build()).collectList().block();

        verify(chatAttachmentService).attachToMessage(eq(List.of(11L)), eq("s2"), eq(66L));
        // 契约补齐（Task 7）：关键词触发转人工分支的用户消息在构造 START 前已经落库，START 携带真实 id
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).userMessageId()).isEqualTo(66L);
    }

    /**
     * I-3 复核修正（回归钉死）：附件关联必须排在 toAgentMessage 通知**之前**。挪到通知之后
     * 曾是一次真实回归——RealtimeNotifier.messageJson 在推送那一刻现查附件，若通知先发生，
     * 附件的 messageId 还没来得及回填，坐席收到的推送 attachments 恒为空。
     */
    @Test
    void streamChat_人工接管态_附件关联先于toAgentMessage通知() {
        ChatSession session = ChatSession.builder().sessionId("s1").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            m.setId(55L);
            return m;
        });

        chatService.streamChat(ChatRequest.builder().sessionId("s1").message("图在这")
                .attachmentIds(List.of(9L)).build()).collectList().block();

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(chatAttachmentService, realtimeNotifier);
        inOrder.verify(chatAttachmentService).attachToMessage(eq(List.of(9L)), eq("s1"), eq(55L));
        inOrder.verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());
    }
}
