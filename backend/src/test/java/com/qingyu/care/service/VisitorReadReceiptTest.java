package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客回执的归属校验（会话须属于当前 app）与 readerId 取值。
 *
 * <p>⚠️ 访客没有 user id，用该会话的 {@code chat_session.id} 作 readerId——一个会话只有一个
 * 访客，绝不能传 null：PostgreSQL 唯一约束里 {@code NULL != NULL}，留 null 会让
 * {@code (session_id,'VISITOR',NULL)} 插入任意多行，约束形同虚设且全程不报错。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisitorReadReceiptTest {

    // 装配与 AgentReadReceiptTest（同一个被测类）完全一致；访客路径不走登录态，无需 StpUtil 桩。
    // ⚠️ ObjectMapper 来自 tools.jackson.databind（Jackson 3），不是 com.fasterxml。
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
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @Mock ReadReceiptService readReceiptService;
    @InjectMocks ChatServiceImpl chatService;

    private static final Long SESSION_PK = 7L;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatSession session(Long appId, Long agentId) {
        ChatSession s = ChatSession.builder()
                .id(SESSION_PK).sessionId("sess-1").status(SessionStatus.AGENT_PROCESSING).build();
        s.setAgentId(agentId);
        s.setTenantId(1L);
        s.setAppId(appId);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(s));
        // markVisitorRead 现在会把 upToMessageId 钳到该会话的最大消息 id（clampToMaxMessageId），
        // 默认给一个远大于本文件其余用例传入值（42/88）的上限，让既有断言不受这条新逻辑干扰；
        // 需要验证钳制本身的用例会在方法体内覆盖这条默认桩。
        when(messageRepository.findTopBySessionIdOrderByIdDesc(SESSION_PK))
                .thenReturn(Optional.of(ChatMessage.builder().id(9999L).build()));
        return s;
    }

    @Test
    @DisplayName("访客回执写入 (VISITOR, chat_session.id) 这一行")
    void 访客回执用会话id作readerId() {
        session(1L, 100L);
        TenantContext.setAppId(1L);
        when(readReceiptService.markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 42L))
                .thenReturn(42L);

        chatService.markVisitorRead("sess-1", 42L);

        // ⚠️ readerId 必须是 chat_session.id，绝不能是 null——PG 唯一约束里 NULL != NULL，
        //    留 null 会让 (session_id,'VISITOR',NULL) 插入任意多行，约束形同虚设且不报错
        verify(readReceiptService).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 42L);
    }

    @Test
    @DisplayName("有坐席接管时把访客已读推给该坐席，载荷带 sessionId")
    void 推给坐席() {
        session(1L, 100L);
        TenantContext.setAppId(1L);
        // ⚠️ 传入值（42）与桩返回值（88）必须不同：若两者相同，即便 markVisitorRead 误传
        // upToMessageId 而非 effective 这条断言也会照样绿，判别力等于零
        when(readReceiptService.markRead(anyLong(), any(), anyLong(), anyLong())).thenReturn(88L);

        chatService.markVisitorRead("sess-1", 42L);

        verify(realtimeNotifier).readReceiptToAgent(1L, 100L, "sess-1", 88L);
    }

    @Test
    @DisplayName("无坐席接管时不推送，但水位线照常落库")
    void 无坐席时只落库不推送() {
        session(1L, null);           // agentId 为 null
        TenantContext.setAppId(1L);
        when(readReceiptService.markRead(anyLong(), any(), anyLong(), anyLong())).thenReturn(42L);

        chatService.markVisitorRead("sess-1", 42L);

        verify(readReceiptService).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 42L);
        verify(realtimeNotifier, never()).readReceiptToAgent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("上报值钳到该会话实际最大消息id，拒绝把伪造的水位线写库")
    void 上报值超过会话最大消息id时被钳制() {
        // 同 AgentReadReceiptTest 的同名用例：接口本身不能信任调用方传来的 upToMessageId，
        // 一个能任意大的值就能让对端永远看到「已读」、或让未读数永久归零。widget 目前只上报
        // 服务端给的真实 id 所以没有暴露，但接口本身仍需兜底。
        session(1L, 100L);
        TenantContext.setAppId(1L);
        // 覆盖 session() 里的默认桩：该会话实际只写到 55 号消息
        when(messageRepository.findTopBySessionIdOrderByIdDesc(SESSION_PK))
                .thenReturn(Optional.of(ChatMessage.builder().id(55L).build()));
        long fakeId = 1_700_000_000_000L; // 与真实最大消息 id 数量级悬殊，断言才有判别力
        when(readReceiptService.markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 55L)).thenReturn(55L);

        chatService.markVisitorRead("sess-1", fakeId);

        verify(readReceiptService).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 55L);
        verify(readReceiptService, never()).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, fakeId);
    }

    @Test
    @DisplayName("会话尚无任何消息时钳到0")
    void 会话无消息时钳到零() {
        session(1L, 100L);
        TenantContext.setAppId(1L);
        when(messageRepository.findTopBySessionIdOrderByIdDesc(SESSION_PK)).thenReturn(Optional.empty());
        when(readReceiptService.markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 0L)).thenReturn(0L);

        chatService.markVisitorRead("sess-1", 42L);

        verify(readReceiptService).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 0L);
    }

    @Test
    @DisplayName("会话不属于当前 app 时拒绝")
    void 拒绝跨应用回执() {
        session(2L, 100L);           // 会话属于 app 2
        TenantContext.setAppId(1L);  // 调用方是 app 1

        assertThatThrownBy(() -> chatService.markVisitorRead("sess-1", 42L))
                .isInstanceOf(InvalidOperationException.class);
        verify(readReceiptService, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("会话不存在时拒绝")
    void 拒绝不存在的会话() {
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.markVisitorRead("nope", 42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
