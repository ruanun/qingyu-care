package com.qingyu.care.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.UiPart;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「连续未命中转人工」接线单测：验证 {@code HandoverRuleService.getNoHitStreakThreshold()}
 * 从死代码变为生效功能——chat() 每轮据检索是否命中更新会话 {@code noHitStreak} 计数（未命中 +1、
 * 命中归 0），达阈值时无论走兜底分支还是正常 LLM 分支都建议转人工；阈值禁用（{@code Integer.MAX_VALUE}）
 * 语义下天然不触发。session 在测试内用同一对象引用模拟跨轮持久化（真实生产靠 @Transactional
 * 脏检查 + 本类显式 sessionRepository.save 双重落库，见 ChatServiceImpl.chat()）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceNoHitStreakTest {

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

    private ChatSession newSession(String sessionId) {
        return ChatSession.builder().sessionId(sessionId).status(SessionStatus.AI_PROCESSING).build();
    }

    /** 兜底分支通用桩：allowNoKnowledge=false，命中知识库会直接触发这条分支返回，无需 mock LLM 调用链。 */
    private void stubFallbackCommon(ChatSession session, String sessionId, int threshold) {
        when(sessionRepository.findBySessionId(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(false).fallbackMessage("暂无相关信息").build());
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(threshold);
    }

    @Test
    void 兜底分支_连续两轮未命中达阈值_第二轮建议转人工_第一轮不触发() {
        ChatSession session = newSession("sess-a");
        stubFallbackCommon(session, "sess-a", 2);
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        ChatRequest request = ChatRequest.builder().sessionId("sess-a").message("你好").build();

        ChatResponse first = chatService.chat(request);
        assertThat(first.isSuggestHandover()).isFalse();
        assertThat(session.getNoHitStreak()).isEqualTo(1);

        ChatResponse second = chatService.chat(request);
        assertThat(second.isSuggestHandover()).isTrue();
        assertThat(session.getNoHitStreak()).isEqualTo(2);
    }

    @Test
    void 兜底分支_阈值禁用MAX_VALUE_连续多轮未命中也不触发() {
        ChatSession session = newSession("sess-c");
        stubFallbackCommon(session, "sess-c", Integer.MAX_VALUE);
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        ChatRequest request = ChatRequest.builder().sessionId("sess-c").message("你好").build();

        for (int i = 1; i <= 5; i++) {
            ChatResponse resp = chatService.chat(request);
            assertThat(resp.isSuggestHandover())
                    .as("第 %d 轮：阈值禁用不应触发", i).isFalse();
            assertThat(session.getNoHitStreak()).isEqualTo(i);
        }
    }

    @Test
    void LLM分支_未命中计数循环_命中后归零_低置信度恒false仍可靠streak单独触发() {
        ChatSession session = newSession("sess-b");
        when(sessionRepository.findBySessionId("sess-b")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("兜底").build());
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(2);
        // 低置信度规则恒不触发：证明本用例的 suggestHandover=true 完全来自 streak，与低置信度规则无关
        when(handoverRuleService.checkLowConfidenceTrigger(anyDouble())).thenReturn(false);
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("系统提示 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        when(uiSpecService.parse(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return new UiSpecService.ParseResult(List.of(UiPart.text(text)), text);
        });
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("好的，还有什么可以帮您", 5));

        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        chatService.setSelf(selfMock);
        ChatRequest request = ChatRequest.builder().sessionId("sess-b").message("你好").build();

        // 第1轮：未命中，未达阈值
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        ChatResponse r1 = chatService.chat(request);
        assertThat(r1.isSuggestHandover()).isFalse();
        assertThat(session.getNoHitStreak()).isEqualTo(1);

        // 第2轮：未命中，达阈值 → 仅靠 streak 触发（checkLowConfidenceTrigger 恒 false）
        ChatResponse r2 = chatService.chat(request);
        assertThat(r2.isSuggestHandover()).isTrue();
        assertThat(session.getNoHitStreak()).isEqualTo(2);

        // 第3轮：命中知识库 → streak 归零，不再建议转人工
        Document hitDoc = new Document("知识库内容", Map.of("score", 0.9));
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of(hitDoc));
        ChatResponse r3 = chatService.chat(request);
        assertThat(r3.isSuggestHandover()).isFalse();
        assertThat(session.getNoHitStreak()).isEqualTo(0);

        // 第4轮：再次未命中 → 从 1 重新计数（证明第3轮确实归零，而非残留 2+1=3 恰好未达阈值的巧合）
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        ChatResponse r4 = chatService.chat(request);
        assertThat(r4.isSuggestHandover()).isFalse();
        assertThat(session.getNoHitStreak()).isEqualTo(1);
    }
}
