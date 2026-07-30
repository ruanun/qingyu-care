package com.qingyu.care.service.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RealtimePresenceRegistry} 单测：ZSET online/offline、onlineAgents 剪僵尸+取列表、
 * Redis 异常降级本地视图、heartbeat 遍历本地在线坐席续期。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class RealtimePresenceRegistryTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private RealtimeHub hub;

    private RealtimePresenceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RealtimePresenceRegistry(redis, hub);
    }

    @Test
    void online_调ZADD写入presenceKey() {
        when(redis.opsForZSet()).thenReturn(zSetOps);

        registry.online(9L, 1L);

        verify(zSetOps).add(eq("qingyu:rt:presence:9"), eq("1"), anyDouble());
    }

    @Test
    void online_redis异常failSoft不抛() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        doThrow(new RuntimeException("redis down")).when(zSetOps).add(anyString(), anyString(), anyDouble());

        assertThatCode(() -> registry.online(9L, 1L)).doesNotThrowAnyException();
    }

    @Test
    void offline_调ZREM移除成员() {
        when(redis.opsForZSet()).thenReturn(zSetOps);

        registry.offline(9L, 1L);

        verify(zSetOps).remove("qingyu:rt:presence:9", "1");
    }

    @Test
    void offline_redis异常failSoft不抛() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        doThrow(new RuntimeException("redis down")).when(zSetOps).remove("qingyu:rt:presence:9", "1");

        assertThatCode(() -> registry.offline(9L, 1L)).doesNotThrowAnyException();
    }

    @Test
    void onlineAgents_先剪僵尸再取列表_返回LongList() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.range(eq("qingyu:rt:presence:9"), eq(0L), eq(-1L)))
                .thenReturn(Set.of("1", "2"));

        List<Long> ids = registry.onlineAgents(9L);

        verify(zSetOps).removeRangeByScore(eq("qingyu:rt:presence:9"), eq(0.0), anyDouble());
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void onlineAgents_range返回null时返回空列表() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertThat(registry.onlineAgents(9L)).isEmpty();
    }

    @Test
    void onlineAgents_redis异常降级本地hub_onlineKeys() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        doThrow(new RuntimeException("redis down"))
                .when(zSetOps).removeRangeByScore(anyString(), anyDouble(), anyDouble());
        when(hub.onlineKeys("agent:9:")).thenReturn(List.of("agent:9:1", "agent:9:2"));

        List<Long> ids = registry.onlineAgents(9L);

        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void heartbeat_遍历本地在线坐席逐个ZADD续期() {
        when(hub.onlineKeys("agent:")).thenReturn(List.of("agent:9:1", "agent:8:2"));
        when(redis.opsForZSet()).thenReturn(zSetOps);

        registry.heartbeat();

        verify(zSetOps).add(eq("qingyu:rt:presence:9"), eq("1"), anyDouble());
        verify(zSetOps).add(eq("qingyu:rt:presence:8"), eq("2"), anyDouble());
    }

    @Test
    void heartbeat_单个key解析失败不影响其余续期() {
        // "agent:x:y" 分段数对但非数字（NumberFormatException）；"agent:bad" 分段数不对（直接 continue）
        when(hub.onlineKeys("agent:")).thenReturn(List.of("agent:x:y", "agent:bad", "agent:9:1"));
        when(redis.opsForZSet()).thenReturn(zSetOps);

        assertThatCode(() -> registry.heartbeat()).doesNotThrowAnyException();

        verify(zSetOps).add(eq("qingyu:rt:presence:9"), eq("1"), anyDouble());
    }

    @Test
    void heartbeat_hub异常failSoft不抛() {
        doThrow(new RuntimeException("boom")).when(hub).onlineKeys("agent:");

        assertThatCode(() -> registry.heartbeat()).doesNotThrowAnyException();
    }
}
