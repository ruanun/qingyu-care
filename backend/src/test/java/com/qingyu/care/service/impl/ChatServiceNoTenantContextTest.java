package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.exception.BusinessException;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 无租户上下文（平台管理员）不得发起对话的守卫单测。
 *
 * <p>存在的理由：平台管理员的 {@code TenantContext.getTenantId()} 恒为 null，而
 * {@code TenantEntityListener} 只从上下文回填、不做 appId→租户反查，于是会话落库时
 * {@code tenant_id} 为 NULL、{@code app_id} 有值 —— 平台管理员自己的会话列表按
 * {@code ChatServiceImpl#getAllSessions} 的越权收口返空页看不到，被测租户的管理员也
 * 看不到（NULL ≠ 其 tenantId），**成为谁都读不到的孤儿数据**。
 *
 * <p>与「列会话返空页」保持同一种态度：无租户上下文时**拒绝创建**，而不是静默写出不可达的行。
 * 开放 API / Widget 不受影响 —— {@code OpenApiAuthInterceptor} 鉴权通过后会写入 tenantId。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceNoTenantContextTest {

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
        chatService.setSelf(chatService);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatRequest request() {
        ChatRequest r = new ChatRequest();
        r.setMessage("你好");
        r.setUserId("user_001");
        return r;
    }

    @Test
    void chat_无租户上下文_拒绝且不落库() {
        // 不设 TenantContext.tenantId：模拟平台管理员
        TenantContext.setAppId(7L);

        assertThatThrownBy(() -> chatService.chat(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");

        // 关键：一行都不能写，否则就是产生孤儿数据
        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void streamChat_无租户上下文_同样拒绝() {
        TenantContext.setAppId(7L);

        assertThatThrownBy(() -> chatService.streamChat(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");

        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void chat_有租户上下文_不被守卫拦截() {
        // 反向守护：正常租户（含开放 API 鉴权后的上下文）必须能过守卫。
        // 后续链路依赖大量 mock，这里只断言「不是被守卫以 BusinessException 挡下」。
        TenantContext.setTenantId(8L);
        TenantContext.setAppId(7L);

        assertThatCode(() -> {
            try {
                chatService.chat(request());
            } catch (BusinessException e) {
                throw new AssertionError("有租户上下文时不应被守卫拦截: " + e.getMessage(), e);
            } catch (RuntimeException ignored) {
                // 其它异常来自未打桩的下游协作者，与本守卫无关
            }
        }).doesNotThrowAnyException();
    }
}
