package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.EmbeddingModelProvider;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放渠道新会话分桶接线单测（createNewSession package-private 直测）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceAbWiringTest {

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
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ChatServiceImpl chatService;

    @Test
    void 开放渠道_新会话落实验标记() {
        when(abTestService.assignForNewSession()).thenReturn(Optional.of(
                new AbTestService.VariantAssignment(7L, "B", 42L)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession session = chatService.createNewSession(
                ChatRequest.builder().message("hi").openChannel(true).build());

        assertThat(session.getExperimentId()).isEqualTo(7L);
        assertThat(session.getVariantName()).isEqualTo("B");
        assertThat(session.getVariantStrategyId()).isEqualTo(42L);
    }

    @Test
    void 非开放渠道_不调用分桶() {
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession session = chatService.createNewSession(
                ChatRequest.builder().message("hi").build());

        verify(abTestService, never()).assignForNewSession();
        assertThat(session.getExperimentId()).isNull();
    }

    @Test
    void 开放渠道_无运行实验_不落标() {
        when(abTestService.assignForNewSession()).thenReturn(Optional.empty());
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession session = chatService.createNewSession(
                ChatRequest.builder().message("hi").openChannel(true).build());

        assertThat(session.getExperimentId()).isNull();
        assertThat(session.getVariantName()).isNull();
        assertThat(session.getVariantStrategyId()).isNull();
    }
}
