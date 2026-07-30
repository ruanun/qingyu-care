package com.qingyu.care.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.MessageRole;
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
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 卡片后端接线单测：chat() 主路（非流式、LLM 分支）拿到带 ```spec 围栏的原始回复后，
 * 须经 UiSpecService 解析出 uiParts、content 落为剥离围栏的纯文本、AI 消息落库带 uiSpec JSON。
 * UiSpecService/ObjectMapper 用真实实例（@Spy 包一层已构造好的真对象）而非 mock——
 * 卡片解析/序列化是本用例要验证的行为本身，mock 会让断言失去意义。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatCardWiringTest {

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
    @Spy UiSpecService uiSpecService = new UiSpecService(new ObjectMapper());
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void chat_主路命中卡片围栏_解析uiParts_content剥离围栏_落库带uiSpec() {
        // self 走代理 REQUIRES_NEW 检索的既有约束（同 ChatServiceRetrievalTxTest）；本用例只关心
        // 检索结果本身（返回空知识但 allowNoKnowledge=true，不触发兜底、继续走 LLM 分支）
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("请稍等").build());
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        // session 由 mocked save() 原样回传，未落自增 id（null）；用 any() 匹配（anyLong() 不匹配 null）
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");

        String rawWithFence = "好的，这是您的订单：\n```spec\n"
                + "{\"type\":\"card\",\"props\":{\"title\":\"订单 #10086\"}}\n"
                + "```\n还需要别的帮助吗？";
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of(rawWithFence, 10));

        ChatResponse resp = chatService.chat(
                ChatRequest.builder().message("查一下我的订单").build());

        // content 已剥离围栏，为纯文本
        assertThat(resp.getContent()).doesNotContain("```spec");
        assertThat(resp.getContent()).contains("好的，这是您的订单").contains("还需要别的帮助吗");

        // uiParts 含解析出的 ui 片段
        assertThat(resp.getUiParts()).isNotNull();
        assertThat(resp.getUiParts()).extracting("kind").contains("ui");

        // 落库的 ASSISTANT 消息带非空 uiSpec JSON，且 content 同样是剥离后的纯文本
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        ChatMessage aiMsg = captor.getAllValues().stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst().orElseThrow();
        assertThat(aiMsg.getUiSpec()).isNotBlank();
        assertThat(aiMsg.getUiSpec()).contains("\"kind\"");
        assertThat(aiMsg.getContent()).doesNotContain("```spec");
    }

    @Test
    void appendCardPrompt_开启且withCards_追加卡片指令() {
        String out = ChatServiceImpl.appendCardPrompt("系统提示", true, true);
        assertThat(out).startsWith("系统提示").isNotEqualTo("系统提示");   // 追加了 CARD_PROMPT
    }

    @Test
    void appendCardPrompt_cardsEnabled关_不追加() {
        assertThat(ChatServiceImpl.appendCardPrompt("系统提示", true, false)).isEqualTo("系统提示");
    }

    @Test
    void appendCardPrompt_非withCards流式路_不追加() {
        assertThat(ChatServiceImpl.appendCardPrompt("系统提示", false, true)).isEqualTo("系统提示");
    }
}
