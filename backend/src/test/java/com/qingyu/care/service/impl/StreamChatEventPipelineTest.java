package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：流式落库产出 DONE 事件的三分支（正常/空回复/异常），
 * 以及「围栏原文落库时经 UiSpecService 解析、content 落纯文本 + ui_spec 落卡片 JSON」。
 *
 * 说明：DONE 事件由 persistStreamedMessages 直接返回（不再走 Sinks 回调），故可脱离 Reactor 直接断言。
 * UiSpecService/ObjectMapper 用真实实例（@Spy 包真对象）——卡片解析正是本用例要验证的行为，
 * mock 会让断言失去意义（同 ChatCardWiringTest 惯例）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatEventPipelineTest {

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

    /** 事务模板直通：把 TransactionCallback 当普通函数调用，保持纯 Mockito 单测特性。 */
    private void stubTransactionPassThrough() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    /** 落库时给消息按序分配自增 id（第 1 条用户消息、第 2 条 AI 消息）。 */
    private void stubSaveWithIdSequence() {
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
    }

    @Test
    void 正常回复落库后DONE携带AI消息真实id() {
        stubTransactionPassThrough();
        stubSaveWithIdSequence();

        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s1").build(), "问题", "你好", 0.8, "[1,2]");

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        // 第 2 次 save 是 AI 消息，id=2；DONE 必须带真实落库 id 而非占位值
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isEqualTo(2L);
    }

    @Test
    void 空回复不落库且DONE的消息id为null() {
        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s2").build(), "问题", "", 0.0, null);

        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
        verify(messageRepository, never()).save(any());
    }

    @Test
    void 落库异常时fail_soft仍返回DONE不抛() {
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("DB挂了"));

        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s3").build(), "问题", "你好", 0.5, null);

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
    }

    @Test
    void 含围栏的原文落库时content剥围栏且ui_spec非空() {
        stubTransactionPassThrough();
        stubSaveWithIdSequence();

        service.persistStreamedMessages(ChatSession.builder().sessionId("s4").build(), "问题",
                "前言\n```spec\n{\"type\":\"card\"}\n```\n后记", 0.9, null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        ChatMessage aiMsg = captor.getAllValues().stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst().orElseThrow();
        assertThat(aiMsg.getContent()).doesNotContain("```").contains("前言").contains("后记");
        assertThat(aiMsg.getUiSpec()).contains("\"kind\"").contains("ui");
    }
}
