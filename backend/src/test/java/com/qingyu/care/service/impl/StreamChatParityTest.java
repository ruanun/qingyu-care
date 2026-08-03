package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.SessionStatus;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：流式与非流式的语义对齐。改造前 streamChat 静默丢失了这里断言的每一项能力
 * （兜底回复 / 连续未命中计数 / 转人工建议 / 知识命中 / 工具与 Skill）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatParityTest {

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

    @BeforeEach
    void seedTenantContext() {
        // streamChat 要求非空租户上下文（见 requireTenantContext）；夹具会话不设 tenantId，
        // 实体侧为 null → 归属判定恒放行，取值任意不影响断言（同 ChatCardWiringTest）
        TenantContext.setTenantId(1L);
        // @InjectMocks 不经容器，@Value("${qingyu.care.cards.enabled:true}") 不会被处理，
        // 字段默认 false；显式设为与生产默认值一致的 true（同 SessionTimeoutSchedulerTest 惯例）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cardsEnabled", true);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** 检索结果由 self 代理返回；单测里把 self 换成受控 mock（同 ChatCardWiringTest）。 */
    private void stubRetrieval(List<Document> docs) {
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(docs);
        service.setSelf(selfMock);
    }

    /** 策略：allowNoKnowledge 决定未命中时走兜底还是继续问模型。 */
    private void stubStrategy(boolean allowNoKnowledge) {
        when(abTestService.resolveVariantStrategy(any(), any())).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(allowNoKnowledge).fallbackMessage("暂时无法回答").build());
    }

    /** 落库时按序分配自增 id（第 1 条用户消息、第 2 条 AI 消息）。 */
    private void stubSaveWithIdSequence() {
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
    }

    /** 走到 LLM 分支所需的其余装配（模板 / 历史 / 场景 / 事务 / 模型流）。 */
    private void stubLlmPath(String reply, ChatToolService.ToolBundle bundle) {
        when(chatToolService.resolve()).thenReturn(bundle);
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        when(modelRouterService.streamWithFallback(eq("default"), any()))
                .thenReturn(Flux.just(SpringAiResponses.of(reply, 7)));
    }

    private ChatStreamEvent.Start startOf(List<ChatStreamEvent> events) {
        return (ChatStreamEvent.Start) events.get(0).payload();
    }

    @Test
    void 未命中且不许自由发挥时流式也走兜底回复且不问模型() {
        stubRetrieval(List.of());
        stubStrategy(false);
        stubSaveWithIdSequence();
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(Integer.MAX_VALUE);

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(ChatStreamEvent.Type.START);
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text()).isEqualTo("暂时无法回答");
        assertThat(((ChatStreamEvent.Done) events.get(2).payload()).messageId()).isEqualTo(2L);
        verify(modelRouterService, never()).streamWithFallback(any(), any());
    }

    @Test
    void 检索未命中时连续未命中计数递增并落库() {
        ChatSession session = ChatSession.builder().sessionId("s1")
                .status(SessionStatus.AI_PROCESSING).noHitStreak(2).build();
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of());
        stubStrategy(false);
        stubSaveWithIdSequence();
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(Integer.MAX_VALUE);

        service.streamChat(ChatRequest.builder().sessionId("s1").message("你好").build())
                .collectList().block();

        assertThat(session.getNoHitStreak()).isEqualTo(3);
        verify(sessionRepository).save(session);
    }

    @Test
    void 检索命中时连续未命中计数归零() {
        ChatSession session = ChatSession.builder().sessionId("s2")
                .status(SessionStatus.AI_PROCESSING).noHitStreak(3).build();
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识内容")));
        stubStrategy(true);
        stubLlmPath("回答", ChatToolService.ToolBundle.empty());

        service.streamChat(ChatRequest.builder().sessionId("s2").message("你好").build())
                .collectList().block();

        assertThat(session.getNoHitStreak()).isZero();
    }

    @Test
    void START携带低置信触发的转人工建议() {
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识内容")));
        stubStrategy(true);
        stubLlmPath("回答", ChatToolService.ToolBundle.empty());
        when(handoverRuleService.checkLowConfidenceTrigger(anyDouble())).thenReturn(true);

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(startOf(events).suggestHandover()).isTrue();
    }

    @Test
    void START携带连续未命中达阈值触发的转人工建议() {
        // 覆盖 suggestHandover 的另一半 OR 分支：低置信恒 false（未桩，默认值），
        // 未命中但 allowNoKnowledge=true 走 LLM 主路（不进兜底分支），streak 达阈值仍应建议转人工
        ChatSession session = ChatSession.builder().sessionId("s9")
                .status(SessionStatus.AI_PROCESSING).noHitStreak(2).build();
        when(sessionRepository.findBySessionId("s9")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of());
        stubStrategy(true);
        stubLlmPath("暂无相关知识，但仍尝试回答", ChatToolService.ToolBundle.empty());
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(3);

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().sessionId("s9").message("你好").build()).collectList().block();

        assertThat(session.getNoHitStreak()).isEqualTo(3);
        assertThat(startOf(events).suggestHandover()).isTrue();
    }

    @Test
    void START携带知识命中列表() {
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识A"), new Document("知识B")));
        stubStrategy(true);
        stubLlmPath("回答", ChatToolService.ToolBundle.empty());

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(startOf(events).knowledgeHits()).hasSize(2);
        assertThat(startOf(events).knowledgeHits().get(0).getContent()).contains("知识A");
        assertThat(startOf(events).knowledgeHits().get(0).getSource()).isEqualTo("unknown");
    }

    @Test
    void 流式解析工具与skill并挂载到模型调用() {
        // 改造前 streamChat 传 List.of() 且从不调用 chatToolService —— 工具与 Skill 在流式下静默失效
        ToolCallback tool = mock(ToolCallback.class);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识内容")));
        stubStrategy(true);
        stubLlmPath("好的", new ChatToolService.ToolBundle(List.of("# 退货政策"), List.of(tool)));

        // 捕获传给 streamWithFallback 的 runner，用深度桩 ChatClient 执行它，
        // 确认「有工具时挂载 toolCallbacks」这条链能真正跑通（不抛、不 NPE）
        var runnerCaptor = org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);

        service.streamChat(ChatRequest.builder().message("怎么退货").build()).collectList().block();

        verify(chatToolService).resolve();
        verify(modelRouterService).streamWithFallback(eq("default"), runnerCaptor.capture());
        org.springframework.ai.chat.client.ChatClient client =
                mock(org.springframework.ai.chat.client.ChatClient.class, RETURNS_DEEP_STUBS);
        assertThat(runnerCaptor.getValue().apply(client)).isNotNull();

        // 钉住 skill 与卡片：捕获传给 client.prompt().messages(...) 的完整消息列表，
        // 首条系统消息须同时含 skill markdown（"退货政策"）与卡片能力 Prompt 片段（CARD_PROMPT 关键词）——
        // 光验证 chatToolService.resolve() 被调用挡不住「解析了但没传进 buildMessages」这类回归
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> msgCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(client.prompt()).messages(msgCaptor.capture());
        String systemText = msgCaptor.getValue().get(0).getText();
        assertThat(systemText).contains("退货政策").contains("结构化卡片能力");

        // 钉住工具挂载：messages(...) 之后的链上必须真调用 toolCallbacks(tools)，
        // 而不只是「构造了 tools 列表但没接到 ChatClient 调用链上」
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> toolCaptor = ArgumentCaptor.forClass(List.class);
        verify(client.prompt().messages(msgCaptor.getValue())).toolCallbacks(toolCaptor.capture());
        assertThat(toolCaptor.getValue()).containsExactly(tool);
    }
}
