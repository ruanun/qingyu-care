package com.qingyu.care.service.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RealtimeRedisBridge} 单测：发布镜像到 Redis（含 origin）、Redis 异常 fail-soft、
 * 收到跨实例消息回灌本地、收到自身回环消息跳过。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class RealtimeRedisBridgeTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RealtimeHub hub;

    private final ObjectMapper om = new ObjectMapper();

    private RealtimeRedisBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new RealtimeRedisBridge(redis, hub, om);
    }

    @Test
    void publish_调convertAndSend带前缀channel与origin() {
        bridge.publish("session:s1", "message", "{\"x\":1}");

        verify(redis).convertAndSend(eq("qingyu:rt:session:s1"), contains("\"origin\""));
    }

    @Test
    void publish_redis异常failSoft不抛() {
        doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(any(), any());

        assertThatCode(() -> bridge.publish("k", "e", "d")).doesNotThrowAnyException();
    }

    @Test
    void handle_origin非本实例_调hub_emitLocal() throws Exception {
        String payload = om.writeValueAsString(Map.of("event", "message", "data", "d", "origin", "OTHER"));

        bridge.handle("qingyu:rt:session:s1", payload);

        verify(hub).emitLocal("session:s1", "message", "d");
    }

    @Test
    void handle_origin为本实例_跳过() throws Exception {
        String payload = om.writeValueAsString(Map.of("event", "e", "data", "d", "origin", bridge.instanceId()));

        bridge.handle("qingyu:rt:session:s1", payload);

        verify(hub, never()).emitLocal(any(), any(), any());
    }
}
