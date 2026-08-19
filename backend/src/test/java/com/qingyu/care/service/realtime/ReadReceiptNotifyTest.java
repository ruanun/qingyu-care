package com.qingyu.care.service.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qingyu.care.repository.ChatAttachmentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RealtimeNotifier#readReceiptToUser} / {@link RealtimeNotifier#readReceiptToAgent} 的
 * SSE {@code read} 事件推送。
 *
 * <p>⚠️ 两个方法绝不能复用 {@link RealtimeNotifier#toUserSessionEvent}：widget（{@code
 * widget/src/main.ts}）对 {@code session} 事件是无条件当文案展示的，复用会让访客每次被坐席读到
 * 消息都冒出一条莫名的状态提示。本类专门断言不发 {@code session} 事件。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ReadReceiptNotifyTest {

    @Mock RealtimeHub hub;
    @Mock ChatAttachmentRepository attachmentRepository;
    @InjectMocks RealtimeNotifier notifier;

    private final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

    @Test
    @DisplayName("推给访客：事件名是 read，走会话 inbox 键")
    void 推给访客走read事件() {
        notifier.readReceiptToUser("sess-1", 42L);

        verify(hub).publish(eq(RealtimeKeys.session("sess-1")), eq("read"), captor.capture());
        assertThat(captor.getValue()).contains("\"upToMessageId\":42").contains("\"sessionId\":\"sess-1\"");
    }

    @Test
    @DisplayName("推给坐席：同样是 read 事件，走坐席单播键，载荷必须带 sessionId")
    void 推给坐席走read事件() {
        notifier.readReceiptToAgent(1L, 100L, "sess-1", 42L);

        verify(hub).publish(eq(RealtimeKeys.agent(1L, 100L)), eq("read"), captor.capture());
        // ⚠️ sessionId 必须在载荷里：坐席可能正看着别的会话，收到时要按 sessionId 归位
        assertThat(captor.getValue()).contains("\"sessionId\":\"sess-1\"");
    }

    @Test
    @DisplayName("事件名不是 session——复用 session 会让 widget 把它当文案显示出来")
    void 不能复用session事件名() {
        notifier.readReceiptToUser("sess-1", 42L);

        verify(hub, never()).publish(any(), eq("session"), any());
    }
}
