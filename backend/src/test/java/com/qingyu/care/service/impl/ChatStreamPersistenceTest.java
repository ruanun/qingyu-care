package com.qingyu.care.service.impl;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：withStreamPersistence 纯方法（内容透传 + 完整文本仅在正常完成时回调）
 * 以及 persistStreamedMessages 的落库+回调必达三分支（正常/空回复/异常）。
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

    @Test
    void 流正常完成_以完整聚合文本回调() {
        AtomicReference<String> full = new AtomicReference<>();
        List<String> out = ChatServiceImpl.withStreamPersistence(Flux.just("你", "好"), full::set)
                .collectList().block();
        assertThat(out).containsExactly("你", "好");
        assertThat(full.get()).isEqualTo("你好");
    }

    @Test
    void 流错误_不回调() {
        AtomicReference<String> full = new AtomicReference<>();
        Flux<String> f = ChatServiceImpl.withStreamPersistence(
                Flux.concat(Flux.just("半"), Flux.error(new RuntimeException("模型中断"))), full::set);
        assertThatThrownBy(f::blockLast).hasMessageContaining("模型中断");
        assertThat(full.get()).isNull();
    }

    @Test
    void 空流完成_以空串回调() {
        AtomicReference<String> full = new AtomicReference<>();
        ChatServiceImpl.withStreamPersistence(Flux.empty(), full::set).collectList().block();
        assertThat(full.get()).isEmpty();
    }

    /**
     * 事务模板直通：把 TransactionCallback 当作普通函数调用，模拟事务内即时提交，
     * 不引入真实事务/容器，保持纯 Mockito 单测特性。
     */
    private void stubTransactionTemplatePassThrough() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    @Test
    void persistStreamedMessages_正常回复_事务内落库两条消息_回调收到AI消息真实id() {
        stubTransactionTemplatePassThrough();
        AtomicLong idSeq = new AtomicLong(0);
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            msg.setId(idSeq.incrementAndGet());
            return msg;
        });

        ChatSession session = ChatSession.builder().sessionId("session-1").build();
        AtomicLong callbackId = new AtomicLong(Long.MIN_VALUE);

        service.persistStreamedMessages(session, "你好", "你好呀，有什么可以帮您？",
                0.8, "[1,2]", callbackId::set);

        verify(messageRepository, times(2)).save(any(ChatMessage.class));
        // 第 2 次 save 对应 AI 消息，id=2；回调必须拿到真实落库 id 而非占位值
        assertThat(callbackId.get()).isEqualTo(2L);
    }

    @Test
    void persistStreamedMessages_空串回复_不落库_回调收到负一() {
        ChatSession session = ChatSession.builder().sessionId("session-2").build();
        AtomicLong callbackId = new AtomicLong(Long.MIN_VALUE);

        service.persistStreamedMessages(session, "你好", "", 0.0, null, callbackId::set);

        verify(messageRepository, never()).save(any());
        assertThat(callbackId.get()).isEqualTo(-1L);
    }

    @Test
    void persistStreamedMessages_落库异常_回调收到负一_不外抛() {
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("数据库连接失败"));

        ChatSession session = ChatSession.builder().sessionId("session-3").build();
        AtomicLong callbackId = new AtomicLong(Long.MIN_VALUE);

        service.persistStreamedMessages(session, "你好", "你好呀", 0.8, null, callbackId::set);

        assertThat(callbackId.get()).isEqualTo(-1L);
    }
}
