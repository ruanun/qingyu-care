package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.exception.InvalidOperationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归 AI（returnToAI）单测：坐席把 AGENT_PROCESSING 会话交还 AI 后，状态须转 AI_PROCESSING、
 * agentId 清空、并向用户推 returned_to_ai 通知；非 AGENT_PROCESSING 状态调用须拒绝，
 * 抛 InvalidOperationException，且不落库不通知。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ReturnToAiTest {

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

    private ChatSession agentProcessingSession() {
        ChatSession session = ChatSession.builder().sessionId("sess-1").build();
        session.setTenantId(2L);
        session.setAppId(5L);
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setAgentId(10L);
        return session;
    }

    @Test
    void returnToAI_AGENT_PROCESSING会话_转AI_PROCESSING_清agentId_并通知用户() {
        ChatSession session = agentProcessingSession();
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        chatService.returnToAI("sess-1");

        assertThat(session.getStatus()).isEqualTo(SessionStatus.AI_PROCESSING);
        assertThat(session.getAgentId()).isNull();
        verify(sessionRepository).save(session);

        ArgumentCaptor<WebhookDomainEvent> captor = ArgumentCaptor.forClass(WebhookDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WebhookDomainEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("session.returned_to_ai");
        assertThat(event.getSessionId()).isEqualTo("sess-1");
        assertThat(event.getTenantId()).isEqualTo(2L);
        assertThat(event.getAppId()).isEqualTo(5L);

        verify(realtimeNotifier).toUserSessionEvent(
                eq("sess-1"), eq("returned_to_ai"), any(String.class));
    }

    @Test
    void returnToAI_非AGENT_PROCESSING状态_抛InvalidOperationException_不落库不通知() {
        ChatSession session = agentProcessingSession();
        session.setStatus(SessionStatus.AI_PROCESSING);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> chatService.returnToAI("sess-1"))
                .isInstanceOf(InvalidOperationException.class);

        verify(sessionRepository, never()).save(any(ChatSession.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(realtimeNotifier, never()).toUserSessionEvent(any(), any(), any());
    }
}
