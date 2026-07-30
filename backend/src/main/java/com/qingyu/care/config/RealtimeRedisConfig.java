package com.qingyu.care.config;

import com.qingyu.care.service.realtime.RealtimeRedisBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * {@link RealtimeRedisBridge} 的 Redis pub/sub 订阅装配：监听所有 {@code qingyu:rt:*} 频道，
 * 收到消息后转交 {@link RealtimeRedisBridge#handle} 回灌本地 Sinks。
 *
 * <p>{@code qingyu.care.realtime.redis.enabled=false} 时整体不装配（含 {@link RealtimeRedisBridge}
 * 自身，见其 {@code @ConditionalOnProperty}），{@code RealtimeHub} 的 bridge 保持 null，
 * 退化为纯单实例本地广播。
 *
 * <p>启动健壮性：沿用 {@code RateLimitConfig} 的「Redis 不可用不阻断启动」范式——
 * {@link RedisMessageListenerContainer} 的订阅连接在后台线程建立，连接失败按内建
 * {@code recoveryInterval} 自动重试，不影响应用启动与其它功能。
 *
 * @author ruan
 */
@Configuration
@ConditionalOnProperty(prefix = "qingyu.care.realtime.redis", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RealtimeRedisConfig {

    @Bean
    public RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     RealtimeRedisBridge bridge) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> bridge.handle(
                        new String(message.getChannel(), StandardCharsets.UTF_8),
                        new String(message.getBody(), StandardCharsets.UTF_8)),
                new PatternTopic(RealtimeRedisBridge.CHANNEL_PREFIX + "*"));
        return container;
    }
}
