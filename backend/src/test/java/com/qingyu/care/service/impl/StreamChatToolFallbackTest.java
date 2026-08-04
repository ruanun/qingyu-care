package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.dto.UiPart;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.DocumentVisibility;
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
        // INTERNAL 而非默认 PUBLIC：管理后台/服务端 apiKey 两类调用方鉴权后写入的都是 INTERNAL，
        // 用非缺省值才能验证「回退真的搬运了 accessLevel」而不是巧合碰上兜底值
        TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
        // @InjectMocks 不经容器，@Value 不会被处理，字段默认 false；显式设为生产默认值
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cardsEnabled", true);
        selfMock = mock(ChatServiceImpl.class);
        // lenient：直接测 fallbackToNonStreaming 的用例不经 streamChat 检索阶段，不会触发这条桩
        lenient().when(selfMock.searchKnowledge(anyString(), any()))
                .thenReturn(List.of(new Document("知识内容")));
        service.setSelf(selfMock);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * 走到 LLM 分支所需装配，不含 streamWithFallback 本身的桩——由调用方按场景各桩一次，
     * 避免「先桩纯 error 流、再桩别的流」这种同一 mock 方法重复 when(...) 触发 UnnecessaryStubbingException。
     */
    private void stubLlmPathWithoutStream(ChatToolService.ToolBundle bundle) {
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
    }

    /** 走到 LLM 分支所需装配，流固定报错（模拟 Spring AI 合并 tool_call chunk 抛异常）。 */
    private void stubStreamFailure(ChatToolService.ToolBundle bundle) {
        stubLlmPathWithoutStream(bundle);
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
        // 断言「chat() 执行的那一刻上下文是对的」，而不是靠测试线程环境残留碰巧成立——
        // 删掉 fallbackToNonStreaming 里 setTenantId/setAppId/setAccessLevel 任一行，本用例须转红
        when(selfMock.chat(any())).thenAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(1L);
            assertThat(TenantContext.getAppId()).isEqualTo(2L);
            assertThat(TenantContext.getAccessLevel()).isEqualTo(DocumentVisibility.INTERNAL);
            return ChatResponse.builder().content("回退回答").messageId(99L).build();
        });

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("下单").build()).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.DELTA,
                        ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text()).isEqualTo("回退回答");
        assertThat(((ChatStreamEvent.Done) events.get(2).payload()).messageId()).isEqualTo(99L);
    }

    @Test
    void 回退成功后补记真实token_不重复计一次调用() {
        // 守护 I-1：meterContentStream 的 doFinally 先于回退发生，已经以 0 token 记过这一次调用
        // （onTokensUsed 恰好回调一次，值为 0）；回退成功拿到真实 token 后，必须走 onFallbackTokensUsed
        // 单独补记，而不是再调一次 onTokensUsed（否则 ApiUsageService.record 会把这一轮重复计成两次调用）
        ToolCallback tool = mock(ToolCallback.class);
        stubStreamFailure(new ChatToolService.ToolBundle(List.of(), List.of(tool)));
        when(selfMock.chat(any())).thenReturn(
                ChatResponse.builder().content("回退回答").messageId(99L).tokenUsage(37).build());

        java.util.List<Integer> onTokensUsedCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Integer> onFallbackTokensUsedCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<ChatStreamEvent> events = service.streamChat(
                        ChatRequest.builder().message("下单").build(),
                        onTokensUsedCalls::add, onFallbackTokensUsedCalls::add)
                .collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.DELTA,
                        ChatStreamEvent.Type.DONE);
        assertThat(onTokensUsedCalls).containsExactly(0);
        assertThat(onFallbackTokensUsedCalls).containsExactly(37);
    }

    @Test
    void 已吐出正文后再失败不回退不重复正文() {
        // 守护 I-1：跑到一半才失败（本例模拟已经吐出一个真实 chunk 后才炸），此时客户端已经拿到半截正文，
        // 回退会把完整答案整段重发、与已发内容拼成「半截+完整」，只有一个字节都没吐出去时才能安全回退
        ToolCallback tool = mock(ToolCallback.class);
        stubLlmPathWithoutStream(new ChatToolService.ToolBundle(List.of(), List.of(tool)));
        when(modelRouterService.streamWithFallback(eq("default"), any())).thenReturn(Flux.concat(
                Flux.just(com.qingyu.care.support.SpringAiResponses.of("已经吐出的正文", 7)),
                Flux.error(new NoSuchElementException("No value present"))));

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("下单").build()).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.START, ChatStreamEvent.Type.DELTA,
                        ChatStreamEvent.Type.ERROR);
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text()).isEqualTo("已经吐出的正文");
        verify(selfMock, never()).chat(any());
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
        // 直接测 fallbackToNonStreaming（package-private，专为同包单测暴露）：
        // subscribeOn(boundedElastic()) 隔离到独立线程后，测试线程自己的 TenantContext 根本不会被
        // 回退触碰，从测试线程读 TenantContext 验证不了「清理确实发生」；正确做法是在同一条响应式链上
        // 用 doOnNext 探针——onErrorResume 的替补 Flux 与 Mono.fromCallable 的 callable 在同一线程上
        // 同步续接（没有额外的线程切换算子），探针能可靠观察到 finally 执行完之后的上下文状态。
        ChatSession session = ChatSession.builder().sessionId("s-fallback").build();
        ChatRequest request = ChatRequest.builder().message("下单").build();
        // 回退本身也失败：finally 必须无条件清理，不能只在成功路径清
        when(selfMock.chat(any())).thenThrow(new RuntimeException("回退内部炸了"));

        // -999L：哨兵值，若探针从未被调用（事件流为空之类的意外），断言应显式失败而非误判通过
        java.util.concurrent.atomic.AtomicReference<Long> tenantIdAfterFinally =
                new java.util.concurrent.atomic.AtomicReference<>(-999L);
        List<ChatStreamEvent> events = service
                .fallbackToNonStreaming(request, session, 1L, 2L, DocumentVisibility.INTERNAL, t -> { })
                .doOnNext(evt -> tenantIdAfterFinally.set(TenantContext.getTenantId()))
                .collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type).containsExactly(ChatStreamEvent.Type.ERROR);
        assertThat(tenantIdAfterFinally.get()).isNull();
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
