package com.qingyu.care.service.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构造 Webhook 事件信封 JSON：{ id, event, createdAt, data }。
 * createdAt 用 ISO_LOCAL_DATE_TIME 显式格式化，避免依赖 Jackson 时间模块配置。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class WebhookPayloadBuilder {

    private final ObjectMapper objectMapper;

    public String build(String id, String event, LocalDateTime createdAt, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("event", event);
        envelope.put("createdAt", createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        envelope.put("data", data);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook payload 序列化失败", e);
        }
    }
}
