package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.MessageRole;
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
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席消息的 role 语义单测。
 *
 * <p>守的是一个真实缺陷：agentReply 长期落库写 ASSISTANT，而 MessageRole.AGENT 从未被写入，
 * 导致访客刷新页面后坐席说过的话被 widget 的历史恢复分支当成 AI 回复渲染
 * （widget/src/history.ts 按 role 分派）。详见
 * docs/superpowers/specs/2026-08-20-agent-message-role-design.md
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatAgentMessageRoleTest {

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
    @Mock ChatAttachmentService chatAttachmentService;
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void agentReply_落库role为AGENT而非ASSISTANT() {
        ChatSession session = ChatSession.builder().sessionId("sess-1").build();
        session.setTenantId(2L);
        session.setAppId(5L);
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setAgentId(10L);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            msg.setId(99L);
            return msg;
        });

        chatService.agentReply("sess-1", "您好，我是人工客服", 10L);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(MessageRole.AGENT);
    }

    /**
     * ⚠️ 别删这条，也别把 buildMessages 里的 AGENT 分支当成冗余代码「清理」掉。
     * 少了那个分支，坐席消息会从 LLM 历史里整段消失——会话从人工退回 AI 之后，
     * AI 看不到坐席刚说过的话，会重复提问或给出与坐席矛盾的答复。
     */
    @Test
    void buildMessages_历史里的AGENT消息也进LLM上下文() {
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        // findTop20...OrderByCreatedAtDesc 返回的是倒序（最新在前），buildMessages 从后往前遍历
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of(
                ChatMessage.builder().role(MessageRole.AGENT).content("已为您登记退款申请").build(),
                ChatMessage.builder().role(MessageRole.USER).content("帮我登记一下").build()));

        List<Message> messages = chatService.buildMessages(
                ChatSession.builder().sessionId("sess-1").build(),
                "现在到哪一步了",
                "暂无相关知识库内容。",
                ChatStrategy.builder().name("默认").scenario("default").build(),
                List.of(),
                false);

        assertThat(messages).filteredOn(AssistantMessage.class::isInstance)
                .extracting(m -> ((AssistantMessage) m).getText())
                .contains("已为您登记退款申请");
    }
}
