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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void 新待接广播给本租户每个在线坐席() {
        when(hub.onlineKeys("agent:9:")).thenReturn(List.of("agent:9:1", "agent:9:2"));
        ChatSession s = ChatSession.builder().sessionId("sess-2").build();
        s.setTenantId(9L);

        notifier.newWaitingToTenantAgents(9L, s);

        verify(hub).publish(org.mockito.ArgumentMatchers.eq("agent:9:1"),
                org.mockito.ArgumentMatchers.eq("session"), org.mockito.ArgumentMatchers.contains("sess-2"));
        verify(hub).publish(org.mockito.ArgumentMatchers.eq("agent:9:2"),
                org.mockito.ArgumentMatchers.eq("session"), org.mockito.ArgumentMatchers.contains("sess-2"));
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
