package com.qingyu.care.service.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebhookPayloadBuilder builder = new WebhookPayloadBuilder(mapper);

    @Test
    void build_producesEnvelopeWithIdEventCreatedAtAndData() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", "s-1");
        data.put("appId", 12);

        String json = builder.build("d-1", "session.ended",
                LocalDateTime.of(2026, 7, 3, 8, 0, 0), data);

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("id").asText()).isEqualTo("d-1");
        assertThat(node.get("event").asText()).isEqualTo("session.ended");
        assertThat(node.get("createdAt").asText()).isEqualTo("2026-07-03T08:00:00");
        assertThat(node.get("data").get("sessionId").asText()).isEqualTo("s-1");
        assertThat(node.get("data").get("appId").asInt()).isEqualTo(12);
    }
}
