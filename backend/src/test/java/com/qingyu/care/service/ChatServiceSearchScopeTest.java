package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.service.embedding.EmbeddingModelRegistry;
import com.qingyu.care.service.embedding.EmbeddingModelResolver;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** searchKnowledge 的范围语义：空范围短路（不触达检索）、非空范围传导 scope 与应用当前的向量模型。 */
@ExtendWith(MockitoExtension.class)
class ChatServiceSearchScopeTest {

    @Mock VectorStore vectorStore;
    @Mock EmbeddingModelRegistry embeddingModelRegistry;
    @Mock EmbeddingModelResolver embeddingModelResolver;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ChatServiceImpl chatService;

    /** 应用当前的向量模型：推荐阈值 0.6，与策略自己的 0.45 不同，才能分辨阈值取自哪边 */
    private static AiModelConfig embeddingModel() {
        return AiModelConfig.builder().id(9L).modelId("bge-m3").modelType(ModelType.EMBEDDING)
                .similarityThreshold(0.6).build();
    }

    @Test
    void 空范围_短路返回空_不触达任何检索路() {
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(new ChatStrategy());
        when(retrievalScopeResolver.resolve()).thenReturn(RetrievalScope.empty(1L));

        List<Document> out = chatService.searchKnowledge("有什么产品");

        assertThat(out).isEmpty();
        verifyNoInteractions(vectorStore, hybridRetrievalService, embeddingModelResolver);
    }

    @Test
    void 非空范围_hybrid开_scope传给混合检索() {
        ChatStrategy strategy = new ChatStrategy();   // hybridEnabled=null → 视为开启
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(strategy);
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL);
        when(retrievalScopeResolver.resolve()).thenReturn(scope);
        AiModelConfig model = embeddingModel();
        when(embeddingModelResolver.active(2L)).thenReturn(model);
        when(hybridRetrievalService.search("有什么产品", scope, strategy, model)).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        // 模型按 scope 的 appId 解析——检索用的是「这个应用」钉住的向量模型
        verify(hybridRetrievalService).search("有什么产品", scope, strategy, model);
    }

    @Test
    void 非空范围_hybrid关_纯向量路带scope过滤() {
        ChatStrategy strategy = new ChatStrategy();
        strategy.setHybridEnabled(false);   // 显式关闭混合检索 → 走纯向量路
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(strategy);
        when(retrievalScopeResolver.resolve()).thenReturn(new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL));
        AiModelConfig model = embeddingModel();
        when(embeddingModelResolver.active(2L)).thenReturn(model);
        when(embeddingModelRegistry.vectorStore(model)).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        verifyNoInteractions(hybridRetrievalService);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        // 确认纯向量路带上了 scope 过滤条件而非裸搜，且护栏认的是应用当前模型的标签
        assertThat(captor.getValue().getFilterExpression()).isNotNull();
        assertThat(captor.getValue().getFilterExpression().toString()).contains("bge-m3");
        // 策略默认跟随向量模型：生效阈值是模型的 0.6，不是策略自己的 0.45
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.6);
    }

    @Test
    void 策略关掉跟随_用策略自己的阈值() {
        ChatStrategy strategy = new ChatStrategy();
        strategy.setHybridEnabled(false);
        strategy.setFollowModelThreshold(false);
        strategy.setSimilarityThreshold(0.5);
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(strategy);
        when(retrievalScopeResolver.resolve()).thenReturn(new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL));
        AiModelConfig model = embeddingModel();
        when(embeddingModelResolver.active(2L)).thenReturn(model);
        when(embeddingModelRegistry.vectorStore(model)).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.5);
    }
}
