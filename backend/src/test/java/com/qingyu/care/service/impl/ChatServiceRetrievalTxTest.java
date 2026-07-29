package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索事务隔离修复的单测：验证 chat() 经 self 代理路由检索（REQUIRES_NEW 才生效），
 * 以及 2 参 searchKnowledge 带 REQUIRES_NEW 只读事务注解（守住核心配置不被误删）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceRetrievalTxTest {

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
    void chat_检索经self代理路由_而非私有自调用() {
        // self 注入为 mock：证明 chat() 调的是 self.searchKnowledge（经代理 REQUIRES_NEW 才生效），
        // 而非绕过代理的私有自调用。空命中 + allowNoKnowledge=false → 走兜底分支返回，不触达 LLM。
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(false).fallbackMessage("请稍等").build());

        chatService.chat(ChatRequest.builder().message("你好").build());

        verify(selfMock).searchKnowledge(eq("你好"), any(ChatStrategy.class));
    }

    @Test
    void chat_检索事务开启失败_仍fail_soft兜底不抛() {
        // self.searchKnowledge 直接抛出 REQUIRES_NEW「开启新事务」阶段的典型异常（连接池耗尽），
        // 该异常落在 searchKnowledge 方法体 try 之外，不会被内部 catch 吞掉；
        // chat() 调用点须自行兜底，验证仍 fail-soft（降级空知识、走兜底话术，不把异常抛给调用方）。
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any()))
                .thenThrow(new CannotCreateTransactionException("pool exhausted"));
        chatService.setSelf(selfMock);

        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(false).fallbackMessage("请稍等").build());

        ChatRequest request = ChatRequest.builder().message("你好").build();

        assertThatCode(() -> chatService.chat(request)).doesNotThrowAnyException();

        var response = chatService.chat(request);
        assertThat(response.getContent()).isEqualTo("请稍等");
        assertThat(response.getKnowledgeHits()).isEmpty();
        assertThat(response.isSuggestHandover()).isFalse();
    }

    @Test
    void 二参检索方法_public且带REQUIRES_NEW只读事务注解() throws Exception {
        Method m = ChatServiceImpl.class.getDeclaredMethod(
                "searchKnowledge", String.class, ChatStrategy.class);
        assertThat(Modifier.isPublic(m.getModifiers()))
                .as("2 参 searchKnowledge 须为 public，代理事务才能拦截自注入调用").isTrue();
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("2 参 searchKnowledge 须带 @Transactional").isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.readOnly()).isTrue();
    }
}
