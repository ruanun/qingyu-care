package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getWaitingAgentSessions 待接队列 appId 收窄单测：有 tenantId+appId 上下文时按双条件收窄
 * repo 方法查询（防跨应用查到其他应用的待接会话）；appId 为空时退回仅 tenantId（管理端无 appId
 * 场景兼容）；无 tenantId（平台管理员）沿用既有空页语义。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WaitingQueueAppScopeTest {

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
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 有tenantId和appId_按双条件收窄查询() {
        TenantContext.setTenantId(2L);
        TenantContext.setAppId(5L);
        Pageable pageable = PageRequest.of(0, 10);
        Page<ChatSession> page = new PageImpl<>(List.of(ChatSession.builder().sessionId("s1").build()));
        when(sessionRepository.findByTenantIdAndAppIdAndStatusOrderByCreatedAtDesc(
                eq(2L), eq(5L), eq(SessionStatus.WAITING_AGENT), eq(pageable))).thenReturn(page);

        Page<ChatSession> result = chatService.getWaitingAgentSessions(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(sessionRepository).findByTenantIdAndAppIdAndStatusOrderByCreatedAtDesc(
                eq(2L), eq(5L), eq(SessionStatus.WAITING_AGENT), eq(pageable));
        verify(sessionRepository, never()).findByTenantIdAndStatusOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void 有tenantId无appId_退回仅tenantId查询() {
        TenantContext.setTenantId(2L);
        Pageable pageable = PageRequest.of(0, 10);
        Page<ChatSession> page = new PageImpl<>(List.of(ChatSession.builder().sessionId("s1").build()));
        when(sessionRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(
                eq(2L), eq(SessionStatus.WAITING_AGENT), eq(pageable))).thenReturn(page);

        Page<ChatSession> result = chatService.getWaitingAgentSessions(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(sessionRepository).findByTenantIdAndStatusOrderByCreatedAtDesc(
                eq(2L), eq(SessionStatus.WAITING_AGENT), eq(pageable));
        verify(sessionRepository, never()).findByTenantIdAndAppIdAndStatusOrderByCreatedAtDesc(
                any(), any(), any(), any());
    }

    @Test
    void 无tenantId_返空页() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<ChatSession> result = chatService.getWaitingAgentSessions(pageable);

        assertThat(result.getTotalElements()).isEqualTo(0);
        verify(sessionRepository, never()).findByTenantIdAndStatusOrderByCreatedAtDesc(any(), any(), any());
        verify(sessionRepository, never()).findByTenantIdAndAppIdAndStatusOrderByCreatedAtDesc(
                any(), any(), any(), any());
    }
}
