package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：persistStreamedMessages 的落库+DONE 事件三分支（正常/空回复/异常）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatStreamPersistenceTest {

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
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ChatServiceImpl service;

    /**
     * 事务模板直通：把 TransactionCallback 当作普通函数调用，模拟事务内即时提交，
     * 不引入真实事务/容器，保持纯 Mockito 单测特性。
     */
    private void stubTransactionTemplatePassThrough() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    @Test
    void persistStreamedMessages_正常回复_事务内落库两条消息_DONE携带AI消息真实id() {
        stubTransactionTemplatePassThrough();
        when(uiSpecService.parse(any())).thenAnswer(inv ->
                new UiSpecService.ParseResult(java.util.List.of(), inv.getArgument(0)));
        AtomicLong idSeq = new AtomicLong(0);
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            msg.setId(idSeq.incrementAndGet());
            return msg;
        });

        ChatSession session = ChatSession.builder().sessionId("session-1").build();

        ChatStreamEvent done = service.persistStreamedMessages(session, "你好", "你好呀，有什么可以帮您？",
                0.8, "[1,2]");

        verify(messageRepository, times(2)).save(any(ChatMessage.class));
        // 第 2 次 save 对应 AI 消息，id=2；DONE 必须带真实落库 id 而非占位值
        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isEqualTo(2L);
    }

    @Test
    void persistStreamedMessages_空串回复_不落库_DONE的消息id为null() {
        ChatSession session = ChatSession.builder().sessionId("session-2").build();

        ChatStreamEvent done = service.persistStreamedMessages(session, "你好", "", 0.0, null);

        verify(messageRepository, never()).save(any());
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
    }

    @Test
    void persistStreamedMessages_落库异常_仍返回DONE_消息id为null_不外抛() {
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("数据库连接失败"));
        when(uiSpecService.parse(any())).thenAnswer(inv ->
                new UiSpecService.ParseResult(java.util.List.of(), inv.getArgument(0)));

        ChatSession session = ChatSession.builder().sessionId("session-3").build();

        ChatStreamEvent done = service.persistStreamedMessages(session, "你好", "你好呀", 0.8, null);

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
    }
}
