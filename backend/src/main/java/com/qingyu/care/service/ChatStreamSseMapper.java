package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * ChatStreamEvent → SSE 行的唯一映射点（管理端与开放端两个控制器共用，保证两端协议一致）。
 * 事件名取枚举小写；payload 一律序列化为 JSON 对象。
 * fail-soft：序列化失败降级为空对象 {}，绝不让一个畸形 payload 打断整条流。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamSseMapper {

    private final ObjectMapper objectMapper;

    public ServerSentEvent<String> toSse(ChatStreamEvent event) {
        return ServerSentEvent.<String>builder(writeJson(event.payload()))
                .event(event.type().name().toLowerCase())
                .build();
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("流式事件序列化失败，降级为空对象: {}", e.getMessage());
            return "{}";
        }
    }
}
