package com.qingyu.care.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.*;
import com.qingyu.care.service.media.ChatAttachmentService;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 守护「发消息时附件真的被关联到【用户消息】上」这条接线。
 * 这类接线最容易在重构中被静默删掉——删了功能照常跑，只是附件永远停留为孤儿、
 * 24 小时后被清理任务连同文件一起删掉，用户看到的是「图发出去了，过一天没了」。
 *
 * <p>装配照搬 ChatCardWiringTest（同类接线用例）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentWiringTest {

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
    @Mock ChatAttachmentService chatAttachmentService;
    @Spy UiSpecService uiSpecService = new UiSpecService(new ObjectMapper());
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ChatServiceImpl chatService;

    @BeforeEach
    void seedTenantContext() {
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 发消息时用用户消息的id关联附件() {
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        // 任务书原始夹具漏了这一桩：请求带 sessionId("s-1") 但未桩 findBySessionId，
        // getOrCreateSession 会判定"未找到"转而 createNewSession 生成随机 UUID，
        // 导致 attachToMessage 收到的第二个参数不是 "s-1" 而是随机会话 id，断言必然落空。
        when(sessionRepository.findBySessionId("s-1")).thenReturn(
                Optional.of(ChatSession.builder().sessionId("s-1").build()));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        // 给保存的消息按顺序发 id：用户消息拿 1，AI 消息拿 2
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("请稍等").build());
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("收到您的截图", 10));

        chatService.chat(ChatRequest.builder()
                .sessionId("s-1")
                .message("这是报错截图")
                .attachmentIds(List.of(7L))
                .build());

        // 第三个参数必须是【用户消息】的 id（1），不是 AI 消息的 id（2）——
        // 挂错了会让附件显示在 AI 的气泡里
        verify(chatAttachmentService).attachToMessage(eq(List.of(7L)), eq("s-1"), eq(1L));
    }

    @Test
    void 没带附件时不调用关联() {
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
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("你好", 10));

        chatService.chat(ChatRequest.builder().sessionId("s-1").message("你好").build());

        verify(chatAttachmentService, never()).attachToMessage(any(), any(), any());
    }

    /**
     * 硬要求 1 的专项守护：给定测试里 request.getSessionId() 与 session.getSessionId() 恰好同值
     * "s-1"，两个来源无法区分——这条用例特意让它们不同值，才能真正抓住"传错成客户端回传值"这类回归。
     *
     * <p>模拟真实越权场景：攻击者在 request 里塞入受害者的 sessionId，但该会话属于另一租户；
     * getOrCreateSession 的归属过滤判定「不可用」，转而新建一个随机 UUID 的会话——
     * 若 attachToMessage 的第二个参数错误地改用 request.getSessionId()（客户端原样回传值），
     * 就会传出 "victim-session" 这个字面量，本用例断言绝不能是它。
     */
    @Test
    void 附件关联使用服务端派生的sessionId_而非客户端回传的request_sessionId() {
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        ChatSession victimSession = ChatSession.builder().sessionId("victim-session").build();
        victimSession.setTenantId(999L);   // 与当前上下文 1L 不同 → 归属校验拒绝
        when(sessionRepository.findBySessionId("victim-session")).thenReturn(Optional.of(victimSession));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("请稍等").build());
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("收到", 10));

        chatService.chat(ChatRequest.builder()
                .sessionId("victim-session")
                .message("附件")
                .attachmentIds(List.of(7L))
                .build());

        // M-5（终审发现）：只断言"不是 victim-session"判别力不够——把参数改成 null 也能通过。
        // 改为捕获真正被新建/保存的会话，断言 attachToMessage 收到的 sessionId 与它逐字相等。
        // atLeastOnce：本用例走到未命中分支会再 save 一次（回写 noHitStreak），两次都是同一个
        // 会话对象引用，取哪次捕获值都一样。
        ArgumentCaptor<ChatSession> savedSessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository, atLeastOnce()).save(savedSessionCaptor.capture());
        String createdSessionId = savedSessionCaptor.getValue().getSessionId();
        assertThat(createdSessionId).isNotEqualTo("victim-session");   // 确认走的是新建会话分支

        verify(chatAttachmentService).attachToMessage(eq(List.of(7L)), eq(createdSessionId), eq(1L));
    }
}
