package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.dto.UiPart;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：流式对话在挂了工具且触发工具调用时，Spring AI 2.0 合并流式 chunk 会抛
 * {@link NoSuchElementException}（上游缺陷 spring-projects/spring-ai#4790，无版本可升）。
 * 本测试守护降级方案：挂了工具的这一轮回退非流式 {@code chat()} 重跑，补发完整事件；
 * 没挂工具则保持现状发 ERROR，不静默重跑掩盖真实故障。
 *
 * <p>另外单独守护最高风险点：回退发生在 Reactor 线程，TenantContext 必须手动搬运
 * 且无条件在 finally 清理——boundedElastic 线程复用，漏清理会把租户上下文泄漏给后续无关请求。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatToolFallbackTest {

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
    @InjectMocks ChatServiceImpl service;

    /** 检索与回退调用（self.searchKnowledge / self.chat）都经这个受控 mock（同 StreamChatParityTest）。 */
    ChatServiceImpl selfMock;

    @BeforeEach
    void seedTenantContext() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        // @InjectMocks 不经容器，@Value 不会被处理，字段默认 false；显式设为生产默认值
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cardsEnabled", true);
        selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of(new Document("知识内容")));
        service.setSelf(selfMock);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 走到 LLM 分支所需装配，流固定报错（模拟 Spring AI 合并 tool_call chunk 抛异常）。 */
    private void stubStreamFailure(ChatToolService.ToolBundle bundle) {
        when(abTestService.resolveVariantStrategy(any(), any())).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("暂时无法回答").build());
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatToolService.resolve()).thenReturn(bundle);
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        when(modelRouterService.streamWithFallback(eq("default"), any()))
                .thenReturn(Flux.error(new NoSuchElementException("No value present")));
    }

    private ChatStreamEvent.Start startOf(List<ChatStreamEvent> events) {
        return (ChatStreamEvent.Start) events.get(0).payload();
    }

    @Test
    void 挂了工具且流失败时回退非流式并补发事件() {
        ToolCallback tool = mock(ToolCallback.class);
        stubStreamFailure(new ChatToolService.ToolBundle(List.of(), List.of(tool)));
        when(selfMock.chat(any())).thenReturn(ChatResponse.builder()
                .content("回退回答").messageId(99L).build());

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("下单").build()).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.DELTA,
                        ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text()).isEqualTo("回退回答");
        assertThat(((ChatStreamEvent.Done) events.get(2).payload()).messageId()).isEqualTo(99L);
    }

    @Test
    void 没挂工具且流失败时发ERROR不回退() {
        stubStreamFailure(ChatToolService.ToolBundle.empty());

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.ERROR);
        verify(selfMock, never()).chat(any());
    }

    @Test
    void 回退结束后租户上下文被清理_含异常路径() {
        ToolCallback tool = mock(ToolCallback.class);
        stubStreamFailure(new ChatToolService.ToolBundle(List.of(), List.of(tool)));
        // 回退本身也失败：finally 必须无条件清理，不能只在成功路径清
        when(selfMock.chat(any())).thenThrow(new RuntimeException("回退内部炸了"));

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("下单").build()).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.ERROR);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void 回退钉住会话ID不新建会话() {
        ToolCallback tool = mock(ToolCallback.class);
        stubStreamFailure(new ChatToolService.ToolBundle(List.of(), List.of(tool)));
        when(selfMock.chat(any())).thenReturn(ChatResponse.builder()
                .content("回退回答").messageId(1L).build());

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("下单").build()).collectList().block();

        String startSessionId = startOf(events).sessionId();
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(selfMock).chat(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo(startSessionId);
    }

    @Test
    void 回退保留卡片顺序() {
        ToolCallback tool = mock(ToolCallback.class);
        stubStreamFailure(new ChatToolService.ToolBundle(List.of(), List.of(tool)));
        Map<String, Object> cardNode = Map.of("type", "card");
        when(selfMock.chat(any())).thenReturn(ChatResponse.builder()
                .messageId(5L)
                .uiParts(List.of(UiPart.text("先说明一下"), UiPart.ui(cardNode)))
                .build());

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("下单").build()).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.DELTA,
                        ChatStreamEvent.Type.CARD, ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text()).isEqualTo("先说明一下");
        assertThat(((ChatStreamEvent.Card) events.get(2).payload()).node()).isEqualTo(cardNode);
    }
}
