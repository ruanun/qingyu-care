package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSession 会话列表的租户级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN 整体不启用
 * @Filter，裸 findAllByOrderByCreatedAtDesc/findByStatusOrderByCreatedAtDesc/
 * findByUserIdOrderByCreatedAtDesc 会让平台管理员跨租户读会话；getAllSessions/getUserSessions/
 * getWaitingAgentSessions 须显式按 TenantContext.getTenantId() 收窄，无 tenantId（平台管理员）
 * 返空页且不查库。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionListScopeTest {

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
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ===== getAllSessions =====

    @Test
    void getAllSessions_有tenantId_status为null_按tenantId收窄() {
        TenantContext.setTenantId(100L);
        Pageable pageable = PageRequest.of(0, 20);
        ChatSession session = ChatSession.builder().id(1L).build();
        when(sessionRepository.findByTenantIdOrderByCreatedAtDesc(100L, pageable))
                .thenReturn(new PageImpl<>(List.of(session)));

        Page<ChatSession> result = chatService.getAllSessions(null, pageable);

        assertThat(result.getContent()).containsExactly(session);
        verify(sessionRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void getAllSessions_有tenantId_status非null_按tenantId和status收窄() {
        TenantContext.setTenantId(100L);
        Pageable pageable = PageRequest.of(0, 20);
        ChatSession session = ChatSession.builder().id(2L).build();
        when(sessionRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(100L, SessionStatus.ENDED, pageable))
                .thenReturn(new PageImpl<>(List.of(session)));

        Page<ChatSession> result = chatService.getAllSessions(SessionStatus.ENDED, pageable);

        assertThat(result.getContent()).containsExactly(session);
        verify(sessionRepository, never()).findByStatusOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void getAllSessions_无tenantId_返空且不查库() {
        Pageable pageable = PageRequest.of(0, 20);

        Page<ChatSession> result = chatService.getAllSessions(null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(sessionRepository, never()).findAllByOrderByCreatedAtDesc(any());
        verify(sessionRepository, never()).findByTenantIdOrderByCreatedAtDesc(anyLong(), any());
        verify(sessionRepository, never()).findByTenantIdAndStatusOrderByCreatedAtDesc(anyLong(), any(), any());
    }

    // ===== getUserSessions =====

    @Test
    void getUserSessions_有tenantId_按tenantId和userId收窄() {
        TenantContext.setTenantId(100L);
        Pageable pageable = PageRequest.of(0, 20);
        ChatSession session = ChatSession.builder().id(3L).userId("u1").build();
        when(sessionRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(100L, "u1", pageable))
                .thenReturn(new PageImpl<>(List.of(session)));

        Page<ChatSession> result = chatService.getUserSessions("u1", pageable);

        assertThat(result.getContent()).containsExactly(session);
        verify(sessionRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void getUserSessions_无tenantId_返空且不查库_防userId猜测跨租户() {
        Pageable pageable = PageRequest.of(0, 20);

        Page<ChatSession> result = chatService.getUserSessions("u1", pageable);

        assertThat(result.getContent()).isEmpty();
        verify(sessionRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
        verify(sessionRepository, never()).findByTenantIdAndUserIdOrderByCreatedAtDesc(anyLong(), any(), any());
    }

    // ===== getWaitingAgentSessions =====

    @Test
    void getWaitingAgentSessions_有tenantId_按tenantId和WAITING_AGENT收窄() {
        TenantContext.setTenantId(100L);
        Pageable pageable = PageRequest.of(0, 20);
        ChatSession session = ChatSession.builder().id(4L).status(SessionStatus.WAITING_AGENT).build();
        when(sessionRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(100L, SessionStatus.WAITING_AGENT, pageable))
                .thenReturn(new PageImpl<>(List.of(session)));

        Page<ChatSession> result = chatService.getWaitingAgentSessions(pageable);

        assertThat(result.getContent()).containsExactly(session);
        verify(sessionRepository, never()).findByStatusOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void getWaitingAgentSessions_无tenantId_返空且不查库() {
        Pageable pageable = PageRequest.of(0, 20);

        Page<ChatSession> result = chatService.getWaitingAgentSessions(pageable);

        assertThat(result.getContent()).isEmpty();
        verify(sessionRepository, never()).findByStatusOrderByCreatedAtDesc(any(), any());
        verify(sessionRepository, never()).findByTenantIdAndStatusOrderByCreatedAtDesc(anyLong(), any(), any());
    }
}
