package com.qingyu.care.service.impl;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.PromptTemplate;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话取策略（变体优先/回退默认）与 Prompt 模板接线单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStrategyResolveTest {

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

    @Test
    void 会话带有效变体_使用变体策略() {
        ChatSession session = ChatSession.builder().build();
        session.setExperimentId(7L);
        session.setVariantStrategyId(42L);
        ChatStrategy variant = ChatStrategy.builder().name("变体B").scenario("default").build();
        when(abTestService.resolveVariantStrategy(7L, 42L)).thenReturn(Optional.of(variant));

        assertThat(chatService.resolveStrategy(session)).isSameAs(variant);
        verify(chatStrategyService, never()).getActiveStrategy(any());
    }

    @Test
    void 无变体_回退默认策略() {
        ChatSession session = ChatSession.builder().build();
        ChatStrategy def = ChatStrategy.builder().name("默认").scenario("default").build();
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(def);

        assertThat(chatService.resolveStrategy(session)).isSameAs(def);
    }

    @Test
    void 策略绑定模板_优先使用其内容() {
        PromptTemplate tpl = PromptTemplate.builder().content("绑定模板 {context}").build();
        when(promptTemplateService.getById(9L)).thenReturn(tpl);

        String result = chatService.resolveSystemTemplate(
                ChatStrategy.builder().promptTemplateId(9L).build());

        assertThat(result).isEqualTo("绑定模板 {context}");
        verify(promptTemplateService, never()).getActiveTemplateContent();
    }

    @Test
    void 绑定模板失效_failSoft回退激活模板() {
        when(promptTemplateService.getById(9L))
                .thenThrow(new ResourceNotFoundException("Prompt 模板", "9"));
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("激活模板");

        assertThat(chatService.resolveSystemTemplate(
                ChatStrategy.builder().promptTemplateId(9L).build())).isEqualTo("激活模板");
    }

    @Test
    void 未绑定模板_使用激活模板() {
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("激活模板");
        assertThat(chatService.resolveSystemTemplate(ChatStrategy.builder().build()))
                .isEqualTo("激活模板");
    }
}
