package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：反馈提交的参数与归属校验分支。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackServiceTest {

    @Mock
    private ChatMessageRepository messageRepository;
    @InjectMocks
    private ChatFeedbackService service;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private ChatMessage aiMessage(String sessionId, Long appId) {
        ChatSession session = ChatSession.builder().sessionId(sessionId).build();
        session.setAppId(appId);
        return ChatMessage.builder().id(10L).session(session)
                .role(MessageRole.ASSISTANT).content("回复").build();
    }

    @Test
    void 合法反馈_落库覆盖() {
        ChatMessage msg = aiMessage("s-1", 100L);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(msg));

        service.submitFeedback("s-1", 10L, 1);

        assertThat(msg.getFeedback()).isEqualTo(1);
        verify(messageRepository).save(msg);
    }

    @Test
    void 非法值_拒绝() {
        assertThatThrownBy(() -> service.submitFeedback("s-1", 10L, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 会话不匹配_按404处理() {
        when(messageRepository.findById(10L)).thenReturn(Optional.of(aiMessage("s-1", 100L)));
        assertThatThrownBy(() -> service.submitFeedback("s-其他", 10L, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 跨应用上下文_按404处理() {
        when(messageRepository.findById(10L)).thenReturn(Optional.of(aiMessage("s-1", 100L)));
        TenantContext.setAppId(999L);
        assertThatThrownBy(() -> service.submitFeedback("s-1", 10L, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 用户消息不可评价() {
        ChatSession session = ChatSession.builder().sessionId("s-1").build();
        ChatMessage userMsg = ChatMessage.builder().id(11L).session(session)
                .role(MessageRole.USER).content("提问").build();
        when(messageRepository.findById(11L)).thenReturn(Optional.of(userMsg));
        assertThatThrownBy(() -> service.submitFeedback("s-1", 11L, -1))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 坐席消息不可评价。此前坐席消息的 role 落成 ASSISTANT，能被当作 AI 回复点赞/点踩，
     * 反馈数据会混进 AI 质量指标；role 修正为 AGENT 之后这条校验自然成立，用例把它钉住。
     */
    @Test
    void 坐席消息不可评价() {
        ChatSession session = ChatSession.builder().sessionId("s-1").build();
        ChatMessage agentMsg = ChatMessage.builder().id(12L).session(session)
                .role(MessageRole.AGENT).content("我是人工客服").build();
        when(messageRepository.findById(12L)).thenReturn(Optional.of(agentMsg));
        assertThatThrownBy(() -> service.submitFeedback("s-1", 12L, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 消息不存在_按404处理() {
        when(messageRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submitFeedback("s-1", 404L, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 重复提交_覆盖旧值() {
        ChatMessage msg = aiMessage("s-1", 100L);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(msg));
        service.submitFeedback("s-1", 10L, 1);
        service.submitFeedback("s-1", 10L, -1);
        assertThat(msg.getFeedback()).isEqualTo(-1);
        verify(messageRepository, times(2)).save(msg);
    }

    @Test
    void 应用上下文与会话一致_放行() {
        ChatMessage msg = aiMessage("s-1", 100L);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(msg));
        TenantContext.setAppId(100L);
        service.submitFeedback("s-1", 10L, 1);
        assertThat(msg.getFeedback()).isEqualTo(1);
    }

    @Test
    void 跨租户上下文_按404处理() {
        ChatMessage msg = aiMessage("s-1", 100L);
        msg.getSession().setTenantId(7L);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(msg));
        TenantContext.setTenantId(999L);
        assertThatThrownBy(() -> service.submitFeedback("s-1", 10L, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 同租户上下文_放行() {
        ChatMessage msg = aiMessage("s-1", 100L);
        msg.getSession().setTenantId(7L);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(msg));
        TenantContext.setTenantId(7L);
        service.submitFeedback("s-1", 10L, 1);
        assertThat(msg.getFeedback()).isEqualTo(1);
    }
}
