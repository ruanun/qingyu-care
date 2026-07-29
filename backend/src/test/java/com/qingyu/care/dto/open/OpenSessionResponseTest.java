package com.qingyu.care.dto.open;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSessionResponseTest {

    // UiPart 仅有 @AllArgsConstructor（无默认构造器），需 ParameterNamesModule 才能按参数名隐式创建；
    // 生产环境注入的是 Spring Boot 自动配置的 ObjectMapper（JacksonAutoConfiguration 默认已注册该模块），
    // 这里手动补注册以在纯 Mockito 单测中还原同等行为。
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule());

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
}
