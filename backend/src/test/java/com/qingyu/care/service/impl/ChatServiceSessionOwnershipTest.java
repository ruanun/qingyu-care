package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ChatSession 按 sessionId 加载的租户归属校验单测：{@code findBySessionId} 不受 @Filter 保护，
 * 加载后须显式用 TenantOwnershipGuard 校验归属，越权按「不存在」同语义处理（同 KnowledgeOwnershipTest 范式）；
 * 无上下文（平台管理员/Reactor 线程无 TenantContext）按 belongsToCurrentContext 语义放行。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceSessionOwnershipTest {

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
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatSession sessionOwnedBy(Long tenantId) {
        ChatSession session = ChatSession.builder().sessionId("sess-1").build();
        session.setTenantId(tenantId);
        return session;
    }

    @Test
    void getSession_本上下文会话_正常返回() {
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(sessionOwnedBy(2L)));
        TenantContext.setTenantId(2L);

        assertThat(chatService.getSession("sess-1")).isNotNull();
    }

    @Test
    void getSession_他租户会话_按不存在处理() {
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(sessionOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> chatService.getSession("sess-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSession_无上下文_放行() {
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(sessionOwnedBy(9L)));

        assertThat(chatService.getSession("sess-1")).isNotNull();
    }
}
