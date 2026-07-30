package com.qingyu.care.service.realtime;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RealtimeNotifierTest {

    @Mock RealtimeHub hub;
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
