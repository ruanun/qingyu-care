package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** searchKnowledge 的范围语义：空范围短路（不触达检索）、非空范围传导 scope。 */
@ExtendWith(MockitoExtension.class)
class ChatServiceSearchScopeTest {

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

    @Test
    void 空范围_短路返回空_不触达任何检索路() {
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(new ChatStrategy());
        when(retrievalScopeResolver.resolve()).thenReturn(RetrievalScope.empty(1L));

        List<Document> out = chatService.searchKnowledge("有什么产品");

        assertThat(out).isEmpty();
        verifyNoInteractions(vectorStore, hybridRetrievalService);
    }

    @Test
    void 非空范围_hybrid开_scope传给混合检索() {
        ChatStrategy strategy = new ChatStrategy();   // hybridEnabled=null → 视为开启
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(strategy);
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL);
        when(retrievalScopeResolver.resolve()).thenReturn(scope);
        when(hybridRetrievalService.search("有什么产品", scope, strategy)).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        verify(hybridRetrievalService).search("有什么产品", scope, strategy);
    }

    @Test
    void 非空范围_hybrid关_纯向量路带scope过滤() {
        ChatStrategy strategy = new ChatStrategy();
        strategy.setHybridEnabled(false);   // 显式关闭混合检索 → 走纯向量路
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(strategy);
        when(retrievalScopeResolver.resolve()).thenReturn(new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        verifyNoInteractions(hybridRetrievalService);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        // 确认纯向量路带上了 scope 过滤条件而非裸搜（M4 的 filterExpression 是 Filter.Expression 对象，断言非 null）
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
    }
}
