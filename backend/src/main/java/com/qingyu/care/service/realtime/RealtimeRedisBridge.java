package com.qingyu.care.service.realtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * {@link RealtimeHub} 的 Redis pub/sub 跨实例桥接：{@link #publish} 把本地已送达的事件
 * 再镜像广播到 Redis 频道，本实例的 {@code RealtimeRedisConfig} 监听容器收到其它实例的
 * 广播后回调 {@link #handle}，回灌本地 Sinks（{@link RealtimeHub#emitLocal}）。
 *
 * <p>语义定位：SSE 是加速通道而非事实源（DB 为事实源、客户端重连补拉），故用
 * pub/sub（fire-and-forget，无持久化/消费组/ACK），Redis 抖动期跨实例消息可能丢，可接受。
 *
 * <p>fail-open：发布/解析异常均 try/catch 仅 log.debug，绝不抛出、不阻断业务；Redis 不可用时
 * 本实例仅退化为单实例语义（本地 Sinks 已由 {@link RealtimeHub#publish} 直接送达）。
 *
 * @author ruan
 */
@Component
@ConditionalOnProperty(prefix = "qingyu.care.realtime.redis", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RealtimeRedisBridge {

    /** Redis pub/sub 频道前缀；订阅键即频道去除本前缀后的部分。（public 供 RealtimeRedisConfig 装配 PatternTopic） */
    public static final String CHANNEL_PREFIX = "qingyu:rt:";

    private final StringRedisTemplate redisTemplate;
    private final RealtimeHub hub;
    private final ObjectMapper objectMapper;

    /** 本实例标识：启动时生成，随广播消息携带，用于回环去重（自己发的消息本地已送达，收到时跳过）。 */
    private final String instanceId = UUID.randomUUID().toString();

    public RealtimeRedisBridge(StringRedisTemplate redisTemplate, RealtimeHub hub, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.hub = hub;
        this.objectMapper = objectMapper;
    }

    /** 供测试读取本实例 id。 */
    String instanceId() {
        return instanceId;
    }

    /**
     * 镜像发布到 Redis 频道，供其它实例的监听容器接收后回灌其本地 Sinks。
     * fail-soft：Redis 异常仅记 debug 日志，本地送达已在 {@link RealtimeHub#publish} 完成，不阻断。
     */
    public void publish(String key, String event, String data) {
        try {
            String payload = objectMapper.writeValueAsString(
                    Map.of("event", event, "data", data, "origin", instanceId));
            redisTemplate.convertAndSend(CHANNEL_PREFIX + key, payload);
        } catch (Exception e) {
            log.debug("Redis 实时消息发布失败（降级单实例）key={} event={}: {}", key, event, e.getMessage());
        }
    }

    /**
     * 处理来自 Redis 频道的消息（由 {@code RealtimeRedisConfig} 的监听容器回调，故为 public）。
     * channel 去前缀还原订阅键；origin 为本实例时跳过（本地已由 {@link RealtimeHub#publish} 直接
     * 送达，避免重复投递）；解析失败同样 fail-soft，只记 debug 日志，不抛出。
     */
    public void handle(String channel, String payload) {
        try {
            String key = channel.startsWith(CHANNEL_PREFIX) ? channel.substring(CHANNEL_PREFIX.length()) : channel;
            JsonNode node = objectMapper.readTree(payload);
            String origin = node.path("origin").asText();
            if (instanceId.equals(origin)) {
                return;
            }
            hub.emitLocal(key, node.path("event").asText(), node.path("data").asText());
        } catch (Exception e) {
            log.debug("Redis 实时消息解析失败 channel={}: {}", channel, e.getMessage());
        }
    }
}
