package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.exception.InvalidOperationException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席回执的归属校验与接管置顶。
 *
 * <p>⚠️ 归属校验是安全边界：缺了它，任何坐席都能靠遍历 sessionId 给<b>别人接管的会话</b>
 * 写水位线，进而伪造一条发给那个访客的「已读」。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentReadReceiptTest {

    // 装配照 ChatSessionListScopeTest（同一个被测类）。ChatServiceImpl 有 19 个依赖，
    // 这里只声明测试路径用得到的；Mockito 会把其余注入为 null，不碰就没事。
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

    /** 当前坐席的 user id。由控制器取好传进来，service 层不碰 StpUtil，故无需任何静态桩。 */
    private static final Long ME = 100L;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatSession session(Long agentId, SessionStatus status) {
        ChatSession s = ChatSession.builder().id(7L).sessionId("sess-1").status(status).build();
        s.setAgentId(agentId);
        s.setTenantId(1L);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    @DisplayName("非本人接管的会话拒绝回执，且一次都不写水位线")
    void 拒绝给别人的会话写水位线() {
        session(999L, SessionStatus.AGENT_PROCESSING);   // 别人接管的

        assertThatThrownBy(() -> chatService.markAgentRead("sess-1", ME, 42L))
                .isInstanceOf(InvalidOperationException.class);
        verify(readReceiptService, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("本人接管的会话正常写入，readerType=AGENT、readerId=坐席 user id")
    void 本人会话正常回执() {
        ChatSession s = session(ME, SessionStatus.AGENT_PROCESSING);
        when(readReceiptService.markRead(s.getId(), ReaderType.AGENT, ME, 42L)).thenReturn(42L);

        chatService.markAgentRead("sess-1", ME, 42L);

        verify(readReceiptService).markRead(s.getId(), ReaderType.AGENT, ME, 42L);
        // TODO(Task 5): readReceiptToUser 由 Task 5 实现，届时放开
        // verify(realtimeNotifier).readReceiptToUser("sess-1", 42L);
    }

    @Test
    @DisplayName("会话已结束时拒绝回执")
    void 会话结束后拒绝回执() {
        session(ME, SessionStatus.ENDED);

        assertThatThrownBy(() -> chatService.markAgentRead("sess-1", ME, 42L))
                .isInstanceOf(InvalidOperationException.class);
        verify(readReceiptService, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("推送的是生效后的水位线，不是传入值")
    void 推送生效值而非传入值() {
        ChatSession s = session(ME, SessionStatus.AGENT_PROCESSING);
        // 并发下另一个标签页已推到 88，本次传 42 不回退
        when(readReceiptService.markRead(s.getId(), ReaderType.AGENT, ME, 42L)).thenReturn(88L);

        chatService.markAgentRead("sess-1", ME, 42L);

        // TODO(Task 5): readReceiptToUser 由 Task 5 实现，届时放开
        // verify(realtimeNotifier).readReceiptToUser("sess-1", 88L);
    }

    @Test
    @DisplayName("acceptSession 把水位线置为当时最新消息 id")
    void 接管瞬间置顶水位线() {
        ChatSession s = session(null, SessionStatus.WAITING_AGENT);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(77L).build()));

        chatService.acceptSession("sess-1", ME);   // 既有签名就是两参

        verify(readReceiptService).markRead(s.getId(), ReaderType.AGENT, ME, 77L);
    }
}
