package com.qingyu.care.dto.open;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSessionResponseTest {

    // UiPart 仅有 @AllArgsConstructor（无默认构造器），需按构造参数名隐式创建。
    // Jackson 3 已把原 jackson-module-parameter-names 并入 databind 且默认启用，
    // 故裸 new ObjectMapper() 即具备该能力，无需再手动 registerModule（Jackson 2 时代才需要）。
    private final ObjectMapper om = new ObjectMapper();

    private ChatSession session() {
        ChatSession s = new ChatSession();
        s.setSessionId("s-1");
        s.setStatus(SessionStatus.AI_PROCESSING);
        return s;
    }

    private ChatMessage msg(MessageRole role, String content, String uiSpec) {
        return ChatMessage.builder().role(role).content(content).uiSpec(uiSpec).build();
    }

    @Test
    void uiSpec非空_反序列化为uiParts() {
        String uiSpec = "[{\"kind\":\"text\",\"text\":\"前言\"},"
                + "{\"kind\":\"ui\",\"node\":{\"type\":\"card\",\"props\":{\"title\":\"订单\"}}}]";
        OpenSessionResponse r = OpenSessionResponse.of(session(),
                List.of(msg(MessageRole.ASSISTANT, "前言", uiSpec)), om);
        assertThat(r.getMessages()).hasSize(1);
        assertThat(r.getMessages().get(0).getUiParts()).hasSize(2);
        assertThat(r.getMessages().get(0).getUiParts().get(1).getNode()).isNotNull();
    }

    @Test
    void uiSpec为空_uiParts为null() {
        OpenSessionResponse r = OpenSessionResponse.of(session(),
                List.of(msg(MessageRole.USER, "你好", null)), om);
        assertThat(r.getMessages().get(0).getUiParts()).isNull();
    }

    @Test
    void uiSpec非法JSON_failSoft返null不抛() {
        OpenSessionResponse r = OpenSessionResponse.of(session(),
                List.of(msg(MessageRole.ASSISTANT, "x", "{不是合法JSON")), om);
        assertThat(r.getMessages().get(0).getUiParts()).isNull();  // 不抛异常
        assertThat(r.getMessages().get(0).getContent()).isEqualTo("x");
    }

    @Test
    void 附件按messageId分组填入对应消息_且回填消息id() {
        ChatMessage m = ChatMessage.builder().id(7L).role(MessageRole.USER).content("图").build();
        AttachmentDTO att = AttachmentDTO.builder().id(1L).fileName("a.png")
                .contentType("image/png").fileSize(100L).build();
        OpenSessionResponse r = OpenSessionResponse.of(session(), List.of(m), om, Map.of(7L, List.of(att)));
        assertThat(r.getMessages().get(0).getId()).isEqualTo(7L);
        assertThat(r.getMessages().get(0).getAttachments()).containsExactly(att);
    }

    @Test
    void 消息无附件时返回空列表而非null() {
        ChatMessage m = ChatMessage.builder().id(8L).role(MessageRole.USER).content("无图").build();
        OpenSessionResponse r = OpenSessionResponse.of(session(), List.of(m), om, Map.of());
        assertThat(r.getMessages().get(0).getAttachments()).isEmpty();
    }

    @Test
    void 消息id为null时不查附件map_不抛NPE() {
        // Map.of() 对 null key 会直接抛 NPE；未持久化/理论上不该出现的消息也不该让整条历史恢复崩掉
        OpenSessionResponse r = OpenSessionResponse.of(session(),
                List.of(msg(MessageRole.USER, "无id", null)), om, Map.of(1L, List.of()));
        assertThat(r.getMessages().get(0).getId()).isNull();
        assertThat(r.getMessages().get(0).getAttachments()).isEmpty();
    }

    @Test
    void 三参重载兼容既有调用方_不含附件() {
        OpenSessionResponse r = OpenSessionResponse.of(session(),
                List.of(msg(MessageRole.USER, "旧调用方", null)), om);
        assertThat(r.getMessages().get(0).getAttachments()).isEmpty();
    }
}
