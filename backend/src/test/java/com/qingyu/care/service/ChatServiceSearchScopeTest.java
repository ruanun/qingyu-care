package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L));
        when(retrievalScopeResolver.resolve()).thenReturn(scope);
        when(hybridRetrievalService.search("有什么产品", scope, strategy)).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        verify(hybridRetrievalService).search("有什么产品", scope, strategy);
    }
}
