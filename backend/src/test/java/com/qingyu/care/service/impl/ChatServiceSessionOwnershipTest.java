package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.embedding.EmbeddingModelResolver;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSession 按 sessionId 加载的租户归属校验单测：{@code findBySessionId} 不受 @Filter 保护，
 * 加载后须显式用 TenantOwnershipGuard 校验归属，越权按「不存在」同语义处理（同 KnowledgeOwnershipTest 范式）；
 * 无上下文（平台管理员/Reactor 线程无 TenantContext）按 belongsToCurrentContext 语义放行。
 *
 * <p>{@code getOrCreateSession} 复用同一套守卫：续接他人（他应用/他租户）会话时视同「未找到」，
 * 走 {@code createNewSession} 新建，防平台管理员跨应用续接会话历史（该方法 private，经公开
 * 入口 {@code chat()} 间接验证，同类注释里的两条建议路径之一）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceSessionOwnershipTest {

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
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelResolver embeddingModelResolver;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ChatServiceImpl chatService;

    @BeforeEach
    void wireSelf() {
        // chat() 现经 self.searchKnowledge 路由；单测无 Spring，self 需手动指向本实例防 NPE
        chatService.setSelf(chatService);
    }

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

    @Test
    void getOrCreateSession_归属相符_经chat复用会话() {
        ChatSession existing = sessionOwnedBy(2L);
        existing.setStatus(SessionStatus.AGENT_PROCESSING);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(existing));
        TenantContext.setTenantId(2L);

        ChatResponse resp = chatService.chat(
                ChatRequest.builder().sessionId("sess-1").message("你好").build());

        // AGENT_PROCESSING 短路分支直接返回同一会话，未新建
        assertThat(resp.getSessionId()).isEqualTo("sess-1");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void getOrCreateSession_归属不符_经chat视同不存在新建会话() {
        ChatSession existing = sessionOwnedBy(9L);
        existing.setStatus(SessionStatus.AGENT_PROCESSING);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(existing));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default").allowNoKnowledge(false).build());
        TenantContext.setTenantId(2L);

        ChatResponse resp = chatService.chat(
                ChatRequest.builder().sessionId("sess-1").message("你好").build());

        // 他租户会话按未找到处理，走 createNewSession 新建，不复用越权 sessionId
        assertThat(resp.getSessionId()).isNotEqualTo("sess-1");
        // atLeastOnce：createNewSession 落一次 + 连续未命中计数更新落一次，本用例只关心「确实新建并落库」，
        // 不耦合与本用例无关的 noHitStreak 特性精确调用次数
        verify(sessionRepository, org.mockito.Mockito.atLeastOnce()).save(any(ChatSession.class));
    }
}
