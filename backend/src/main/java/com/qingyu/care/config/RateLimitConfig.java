package com.qingyu.care.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * 限流所需的 Redis(Lettuce) + Bucket4j ProxyManager 装配。
 *
 * <p>启动健壮性策略：
 * <ul>
 *   <li>RedisClient bean 仅保存连接参数，不建立实际网络连接（安全）。</li>
 *   <li>StatefulRedisConnection 与 ProxyManager 加 {@literal @Lazy}，只在首次限流时才真正连 Redis。</li>
 * </ul>
 * 因此 Redis 未启动时应用可正常启动，限流组件延迟初始化。
 */
@Configuration
public class RateLimitConfig {

    /**
     * Lettuce RedisClient：仅保存连接参数，本身不建立连接。
     * destroyMethod = "shutdown" 确保应用关闭时优雅释放资源。
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create("redis://" + host + ":" + port);
    }

    /**
     * 实际 Redis 连接：@Lazy 确保首次使用时才建立，启动时不连 Redis。
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient rateLimitRedisClient) {
        return rateLimitRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    /**
     * Bucket4j ProxyManager：@Lazy 跟随连接延迟创建，Key 类型为 String。
     * 令牌桶过期时间设为 120 秒（能完整覆盖 1 分钟窗口并留有余量）。
     */
    @Bean
    @Lazy
    public ProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> rateLimitRedisConnection) {
        return Bucket4jLettuce.casBasedBuilder(rateLimitRedisConnection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(120)))
                .build();
    }
}
