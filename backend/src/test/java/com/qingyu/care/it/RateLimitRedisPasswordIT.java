package com.qingyu.care.it;

import com.qingyu.care.config.RateLimitConfig;
import com.qingyu.care.service.RateLimitService;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流在「Redis 设了密码」时仍然真的生效（2026-09-27 真机冒烟发现）。
 *
 * <p>{@link RateLimitConfig} 为 Bucket4j 自建 Lettuce 连接，不继承 {@code spring.data.redis.password}。
 * 此前只拼 host:port，生产按安全扫描 M4 给 Redis 加上 requirepass 后每次都 NOAUTH，而 {@link RateLimitService}
 * 按设计 fail-open——开放 API 与登录的全部限流静默失效。其余 IT 连的都是无密码的本机 Redis，看不见这个问题。
 *
 * <p>断言的是「超过上限后真的返回 false」，而不是「连得上」：fail-open 时 tryConsume 恒为 true，
 * 只有看到拒绝才能证明请求确实落到了 Redis。用配置类里真实的 bean 方法搭链路，绕开它的测试守不住这一段。
 *
 * @author ruan
 */
class RateLimitRedisPasswordIT {

    private static final String PASSWORD = "it_ratelimit_pw_42";

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisClient client;
    private StatefulRedisConnection<String, byte[]> connection;

    @AfterEach
    void tearDown() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    private RateLimitService serviceWithPassword(String password) {
        RateLimitConfig config = new RateLimitConfig();
        client = config.rateLimitRedisClient(REDIS.getHost(), REDIS.getMappedPort(6379), password);
        connection = config.rateLimitRedisConnection(client);
        ProxyManager<String> proxyManager = config.rateLimitProxyManager(connection);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProxyManager<String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(proxyManager);
        when(provider.getObject()).thenReturn(proxyManager);
        return new RateLimitService(provider);
    }

    @Test
    void 带密码的Redis上超过上限即拒绝() {
        RateLimitService svc = serviceWithPassword(PASSWORD);
        String key = "it-pw-" + System.nanoTime();

        assertThat(svc.tryConsume(key, 2)).isTrue();
        assertThat(svc.tryConsume(key, 2)).isTrue();
        assertThat(svc.tryConsume(key, 2)).as("第 3 次必须被拒；若是 true，说明连接没认证上、走了 fail-open").isFalse();
    }
}
