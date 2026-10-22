package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatMessage.ui_spec 列在真实 Postgres 容器下的持久化往返：带卡片 JSON 的消息读回一致，
 * 纯文本消息（uiSpec 未设置）读回为 null（不落噪声）。
 *
 * @author ruan
 */
class ChatMessageUiSpecIT extends AbstractPostgresIT {

    @Autowired
    private ChatSessionRepository sessionRepository;
    @Autowired
    private ChatMessageRepository messageRepository;

    @Test
    void uiSpec带卡片JSON_保存并读回一致() {
        Application app = seedApp("uispec" + System.nanoTime(), "http://uispec.example.com");
        ChatSession session = ChatSession.builder()
                .sessionId("uispec-session-" + System.nanoTime())
                .userId("u").status(SessionStatus.AI_PROCESSING).build();
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        session = sessionRepository.saveAndFlush(session);

        String uiSpecJson = "[{\"kind\":\"text\",\"text\":\"好的\"},"
                + "{\"kind\":\"ui\",\"node\":{\"type\":\"card\",\"props\":{\"title\":\"订单\"}}}]";
        ChatMessage saved = messageRepository.saveAndFlush(ChatMessage.builder()
                .session(session).role(MessageRole.ASSISTANT)
                .content("好的").uiSpec(uiSpecJson).build());

        ChatMessage reloaded = messageRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUiSpec()).isEqualTo(uiSpecJson);
    }

    @Test
    void uiSpec未设置_纯文本消息读回为null() {
        Application app = seedApp("uispecnull" + System.nanoTime(), "http://uispecnull.example.com");
        ChatSession session = ChatSession.builder()
                .sessionId("uispec-null-session-" + System.nanoTime())
                .userId("u").status(SessionStatus.AI_PROCESSING).build();
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        session = sessionRepository.saveAndFlush(session);

        ChatMessage saved = messageRepository.saveAndFlush(ChatMessage.builder()
                .session(session).role(MessageRole.ASSISTANT)
                .content("纯文本回复，无卡片").build());

        ChatMessage reloaded = messageRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUiSpec()).isNull();
    }
}
