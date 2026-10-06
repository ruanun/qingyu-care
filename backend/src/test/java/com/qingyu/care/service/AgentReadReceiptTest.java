package com.qingyu.care.service;

import com.qingyu.care.service.embedding.EmbeddingModelResolver;
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
import com.qingyu.care.service.embedding.EmbeddingModelRegistry;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock EmbeddingModelRegistry embeddingModelRegistry;
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
    @Mock EmbeddingModelResolver embeddingModelResolver;
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
        // markAgentRead 现在会把 upToMessageId 钳到该会话的最大消息 id（clampToMaxMessageId），
        // 默认给一个远大于本文件其余用例传入值（42/77/88）的上限，让既有断言不受这条新逻辑
        // 干扰；需要验证钳制本身的用例会在各自方法体内覆盖这条默认桩。
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(9999L).build()));
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
        verify(realtimeNotifier).readReceiptToUser("sess-1", 42L);
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

        verify(realtimeNotifier).readReceiptToUser("sess-1", 88L);
    }

    @Test
    @DisplayName("acceptSession 把水位线置为当时最新消息 id")
    void 接管瞬间置顶水位线() {
        ChatSession s = session(null, SessionStatus.WAITING_AGENT);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(77L).build()));

        chatService.acceptSession("sess-1", ME);   // 既有签名就是两参

        // acceptSession 自身带 @Transactional：必须走 REQUIRES_NEW 的 markReadInNewTransaction，
        // 否则并发首次回执 insert 撞唯一键会把这个事务标成 rollback-only（PG 25P02），
        // 连累会话接管本身失败——见 ReadReceiptService 类注释。
        verify(readReceiptService).markReadInNewTransaction(s.getId(), ReaderType.AGENT, ME, 77L);
    }

    @Test
    @DisplayName("两条写水位线路径不可混用：坐席上报走 markRead，接管置顶走独立事务的 markReadInNewTransaction")
    void 两条路径调用不同方法_防止误合并重新暴露25P02风险() {
        // markAgentRead 本身不在任何外层事务里，无需额外开新事务，应调用 markRead
        ChatSession replySession = session(ME, SessionStatus.AGENT_PROCESSING);
        when(readReceiptService.markRead(replySession.getId(), ReaderType.AGENT, ME, 42L)).thenReturn(42L);

        chatService.markAgentRead("sess-1", ME, 42L);

        verify(readReceiptService).markRead(replySession.getId(), ReaderType.AGENT, ME, 42L);
        verify(readReceiptService, never()).markReadInNewTransaction(any(), any(), any(), any());

        // acceptSession 自身带 @Transactional，必须调 markReadInNewTransaction 而非 markRead，
        // 否则 insert 唯一键冲突会连累接管本身失败（同上一条测试的理由）
        ChatSession acceptSession = session(null, SessionStatus.WAITING_AGENT);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findTopBySessionIdOrderByIdDesc(acceptSession.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(77L).build()));

        chatService.acceptSession("sess-1", ME);

        verify(readReceiptService).markReadInNewTransaction(acceptSession.getId(), ReaderType.AGENT, ME, 77L);
        verify(readReceiptService, never()).markRead(acceptSession.getId(), ReaderType.AGENT, ME, 77L);
    }

    @Test
    @DisplayName("上报值钳到该会话实际最大消息id，拒绝把本地占位id当水位线写库")
    void 上报值超过会话最大消息id时被钳制() {
        // 回归本特性合并前审查抓到的必修缺陷：坐席端曾把 Date.now() 占位 id（~1.7e12 量级）
        // 当成已渲染的最新消息 id 上报。接口本身不能信任调用方传来的 upToMessageId——
        // 客户端本轮已同步修复，但这层服务端兜底同样必须存在（同一个坑对访客侧一样成立）。
        ChatSession s = session(ME, SessionStatus.AGENT_PROCESSING);
        // 覆盖 session() 里的默认桩：该会话实际只写到 55 号消息
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(55L).build()));
        long fakeId = 1_700_000_000_000L; // 与真实最大消息 id 数量级悬殊，断言才有判别力
        when(readReceiptService.markRead(s.getId(), ReaderType.AGENT, ME, 55L)).thenReturn(55L);

        chatService.markAgentRead("sess-1", ME, fakeId);

        // 必须钳到 55，绝不能把 fakeId 原样传给 readReceiptService——否则水位线被推到天文
        // 数字，此后该会话所有真实消息都满足不了 id > upToMessageId，未读数永久归零
        verify(readReceiptService).markRead(s.getId(), ReaderType.AGENT, ME, 55L);
        verify(readReceiptService, never()).markRead(s.getId(), ReaderType.AGENT, ME, fakeId);
    }

    @Test
    @DisplayName("会话尚无任何消息时钳到0")
    void 会话无消息时钳到零() {
        ChatSession s = session(ME, SessionStatus.AGENT_PROCESSING);
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId())).thenReturn(Optional.empty());
        when(readReceiptService.markRead(s.getId(), ReaderType.AGENT, ME, 0L)).thenReturn(0L);

        chatService.markAgentRead("sess-1", ME, 42L);

        verify(readReceiptService).markRead(s.getId(), ReaderType.AGENT, ME, 0L);
    }

    @Test
    @DisplayName("接管时置顶已读水位线失败不拖累会话接管本身（已读是增强，不能反过来伤主流程）")
    void 接管路径已读回执异常不影响会话接管() {
        ChatSession s = session(null, SessionStatus.WAITING_AGENT);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(77L).build()));
        // 模拟 REQUIRES_NEW 新事务内 insert 撞唯一键、重试 UPDATE 也撞 25P02，异常从
        // markReadInNewTransaction 里抛出来
        when(readReceiptService.markReadInNewTransaction(s.getId(), ReaderType.AGENT, ME, 77L))
                .thenThrow(new RuntimeException("模拟已读水位线写入失败（如 25P02）"));

        ChatSession result = chatService.acceptSession("sess-1", ME);   // 不应抛出

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(SessionStatus.AGENT_PROCESSING);
        assertThat(result.getAgentId()).isEqualTo(ME);
        verify(sessionRepository).save(any(ChatSession.class));   // 会话接管本身照常落库
    }
}
