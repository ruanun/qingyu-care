package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.entity.ChatSession;
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
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * streamChat 与 chat() 对齐单测：验证接管态 / 关键词转人工下 streamChat 不调用 AI、
 * 返回与 chat() 一致的固定静默文案，并把用户消息 / 新待接事件实时推给坐席（复用 chat() 抽取的
 * isAgentControlled / triggerKeywordHandoverIfHit / persistUserMessageDuringHandover 三个私有方法）。
 * 另补一个 chat() 的等价静默用例，确认抽取未破坏同步路径的既有行为。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatHandoverTest {

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
    @InjectMocks ChatServiceImpl chatService;

    @BeforeEach
    void wireSelf() {
        // chat()/streamChat() 经 self.searchKnowledge 路由；单测无 Spring，self 需手动指向本实例防 NPE
        chatService.setSelf(chatService);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void streamChat_人工接管态_静默不调AI_推用户消息给坐席() {
        ChatSession session = ChatSession.builder().sessionId("s1").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // TenantContext 归属放行（无上下文即放行，见 belongsToCurrentContext）

        List<String> out = chatService.streamChat(
                ChatRequest.builder().sessionId("s1").message("你好").build()).collectList().block();

        assertThat(String.join("", out)).contains("人工客服");
        verify(modelRouterService, never()).getChatClientForScenario(any());
        verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());
    }

    @Test
    void streamChat_等待接管态_不推坐席_仅静默() {
        // WAITING_AGENT 尚未被坐席接管（agentId 为空），只静默存消息，不推 toAgentMessage
        ChatSession session = ChatSession.builder().sessionId("s0").build();
        session.setStatus(SessionStatus.WAITING_AGENT);
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId("s0")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        List<String> out = chatService.streamChat(
                ChatRequest.builder().sessionId("s0").message("你好").build()).collectList().block();

        assertThat(String.join("", out)).contains("人工客服");
        verify(modelRouterService, never()).getChatClientForScenario(any());
        verify(realtimeNotifier, never()).toAgentMessage(any(), any(), any());
    }

    @Test
    void streamChat_命中关键词转人工_不调AI_广播新待接() {
        ChatSession session = ChatSession.builder().sessionId("s2").build();
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(handoverRuleService.checkKeywordTrigger("人工")).thenReturn(true);

        List<String> out = chatService.streamChat(
                ChatRequest.builder().sessionId("s2").message("人工").build()).collectList().block();

        assertThat(String.join("", out)).contains("转接人工客服");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.WAITING_AGENT);
        verify(modelRouterService, never()).getChatClientForScenario(any());
        verify(realtimeNotifier).newWaitingToTenantAgents(eq(9L), eq(session));
    }

    @Test
    void streamChat_已接管态_发含关键词消息_仍静默不重复触发转人工() {
        // 会话已是 AGENT_PROCESSING（正被坐席接管），此时用户发含转人工关键词的消息，
        // streamChat 应先判人工接管态静默（与 chat() 同序），而不是被关键词打回 WAITING_AGENT
        ChatSession session = ChatSession.builder().sessionId("s2").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // 正确顺序下已接管态短路、不会再走到关键词判断，此桩仅用于证明"若顺序颠倒会误判"，标 lenient 防误报
        lenient().when(handoverRuleService.checkKeywordTrigger("我要转人工")).thenReturn(true);

        List<String> out = chatService.streamChat(
                ChatRequest.builder().sessionId("s2").message("我要转人工").build()).collectList().block();

        assertThat(String.join("", out)).contains("人工客服");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.AGENT_PROCESSING);   // 未被打回 WAITING_AGENT
        verify(eventPublisher, never()).publishEvent(any());                          // 不重复发 handover 事件
        verify(realtimeNotifier, never()).newWaitingToTenantAgents(anyLong(), any()); // 不广播新待接
        verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());               // 静默转发给接管坐席
    }

    @Test
    void chat_人工接管态_静默不调AI_推用户消息给坐席_抽取未破坏同步路径() {
        ChatSession session = ChatSession.builder().sessionId("s3").build();
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setTenantId(9L);
        session.setAgentId(3L);
        when(sessionRepository.findBySessionId("s3")).thenReturn(Optional.of(session));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatResponse resp = chatService.chat(
                ChatRequest.builder().sessionId("s3").message("你好").build());

        assertThat(resp.getContent()).isEqualTo("[会话已转接人工客服，请等待回复]");
        assertThat(resp.isSuggestHandover()).isFalse();
        verify(modelRouterService, never()).callWithFallback(any(), any());
        verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());
    }
}
