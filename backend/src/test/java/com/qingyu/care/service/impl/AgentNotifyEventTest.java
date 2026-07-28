package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席接管/回复双向实时推送 + Webhook 事件单测：
 * acceptSession 成功后须推 session.accepted 事件 + toUserSessionEvent 通知用户；
 * agentReply 成功后须推 agent.replied 事件 + toUserMessage 把已落库的坐席消息推给用户。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AgentNotifyEventTest {

    @Mock VectorStore vectorStore;
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
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatSession waitingSession() {
        ChatSession session = ChatSession.builder().sessionId("sess-1").build();
        session.setTenantId(2L);
        session.setAppId(5L);
        session.setStatus(SessionStatus.WAITING_AGENT);
        return session;
    }

    @Test
    void acceptSession_成功后_发session_accepted事件_并推用户会话事件() {
        ChatSession session = waitingSession();
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        chatService.acceptSession("sess-1", 10L);

        ArgumentCaptor<WebhookDomainEvent> captor = ArgumentCaptor.forClass(WebhookDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WebhookDomainEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("session.accepted");
        assertThat(event.getSessionId()).isEqualTo("sess-1");
        assertThat(event.getTenantId()).isEqualTo(2L);
        assertThat(event.getAppId()).isEqualTo(5L);

        verify(realtimeNotifier).toUserSessionEvent(
                eq("sess-1"), eq("accepted"), any(String.class));
    }

    @Test
    void agentReply_成功后_发agent_replied事件_并把落库消息推给用户() {
        ChatSession session = waitingSession();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setAgentId(10L);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(session));
        // 模拟落库回填自增 id：接住 save 返回值才能拿到带 id 的 agentMsg
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            msg.setId(99L);
            return msg;
        });

        chatService.agentReply("sess-1", "您好，我是人工客服", 10L);

        ArgumentCaptor<WebhookDomainEvent> eventCaptor = ArgumentCaptor.forClass(WebhookDomainEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("agent.replied");
        assertThat(eventCaptor.getValue().getSessionId()).isEqualTo("sess-1");

        ArgumentCaptor<ChatMessage> msgCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(realtimeNotifier).toUserMessage(eq("sess-1"), msgCaptor.capture());
        ChatMessage pushed = msgCaptor.getValue();
        assertThat(pushed.getId()).isEqualTo(99L);
        assertThat(pushed.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(pushed.getContent()).isEqualTo("您好，我是人工客服");
    }
}
