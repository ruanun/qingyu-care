package com.qingyu.care.service.realtime;

import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeNotifierTest {

    @Mock RealtimeHub hub;
    @Mock ChatAttachmentRepository attachmentRepository;
    @InjectMocks RealtimeNotifier notifier;

    @Test
    void 坐席消息推到用户inbox_message事件_含content() {
        ChatMessage msg = ChatMessage.builder().role(MessageRole.ASSISTANT).content("您好").build();
        notifier.toUserMessage("sess-1", msg);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(hub).publish(org.mockito.ArgumentMatchers.eq("session:sess-1"),
                org.mockito.ArgumentMatchers.eq("message"), data.capture());
        assertThat(data.getValue()).contains("您好").contains("ASSISTANT");
    }

    /**
     * MM-6：messageJson 补的 attachments/uiSpec 两个字段——用户发的图/AI 卡片不带上，
     * 双向实时推送只剩一半（坐席端永远看不到用户刚发的图，见 CLAUDE.md 相关记述）。
     */
    @Test
    void 消息推送_含attachments与uiSpec字段() {
        ChatMessage msg = ChatMessage.builder().role(MessageRole.USER).content("报错截图").build();
        msg.setId(42L);
        msg.setUiSpec("[{\"kind\":\"text\",\"text\":\"hi\"}]");
        ChatAttachment attachment = ChatAttachment.builder().fileName("a.png").contentType("image/png").build();
        attachment.setId(7L);
        when(attachmentRepository.findByMessageIdInOrderById(List.of(42L))).thenReturn(List.of(attachment));

        notifier.toUserMessage("sess-1", msg);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(hub).publish(org.mockito.ArgumentMatchers.eq("session:sess-1"),
                org.mockito.ArgumentMatchers.eq("message"), data.capture());
        assertThat(data.getValue()).contains("\"attachments\"").contains("a.png")
                .contains("\"uiSpec\"").contains("hi");
    }

    @Test
    void 消息id为空时不查附件_不抛异常() {
        ChatMessage msg = ChatMessage.builder().role(MessageRole.ASSISTANT).content("你好").build();

        notifier.toUserMessage("sess-1", msg);

        verify(attachmentRepository, never()).findByMessageIdInOrderById(any());
    }

    @Test
    void 新待接广播走租户广播键_不再枚举本地在线坐席() {
        ChatSession s = ChatSession.builder().sessionId("sess-2").build();
        s.setTenantId(9L);

        notifier.newWaitingToTenantAgents(9L, s);

        verify(hub).publish(org.mockito.ArgumentMatchers.eq("agent-bcast:9"),
                org.mockito.ArgumentMatchers.eq("session"), org.mockito.ArgumentMatchers.contains("sess-2"));
        verify(hub, never()).onlineKeys(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void presence广播走租户广播键() {
        notifier.presenceToTenantAgents(9L, 1L, true);

        verify(hub).publish(org.mockito.ArgumentMatchers.eq("agent-bcast:9"),
                org.mockito.ArgumentMatchers.eq("presence"),
                org.mockito.ArgumentMatchers.contains("\"online\":true"));
        verify(hub, never()).onlineKeys(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 会话事件_text为null_不抛异常仍发布() {
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> notifier.toUserSessionEvent("s9", "ended", null))
                .doesNotThrowAnyException();
        verify(hub).publish(org.mockito.ArgumentMatchers.eq("session:s9"),
                org.mockito.ArgumentMatchers.eq("session"), org.mockito.ArgumentMatchers.anyString());
    }
}
