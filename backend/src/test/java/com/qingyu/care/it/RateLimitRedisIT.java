package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放 API 限流真 Redis 端到端 IT。
 *
 * <p>存在的唯一理由：限流是 fail-open 的（Redis 侧任何异常都放行），
 * 所以「限流组件坏掉」不会让任何现有测试变红——只会静默放行。本类断言
 * 「超额请求必须真的拿到 429 + Retry-After」，是升级 Lettuce/bucket4j 时
 * 唯一能发现静默失效的哨兵。改动限流链路后必须跑通本类。
 *
 * <p>注意：本类自带 Redis 动态属性，会另起一个 Spring 上下文，不与其它 IT 共享
 * （与 {@link WebhookQueueIT} 同款写法）。
 *
 * @author ruan
 */
class RateLimitRedisIT extends AbstractPostgresIT {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    // applicationRepository 由 AbstractPostgresIT 以 protected 字段提供，不要在此重复 @Autowired
    @Autowired MockMvc mockMvc;

    @Test
    void 超出每分钟额度的请求返回429并带RetryAfter() throws Exception {
        // 每个用例用独立 appKey，避免复用同一 Redis 令牌桶导致相互干扰
        Application app = seedApp("ratelimit", "http://it.example.com");
        app.setRateLimitPerMinute(2);
        applicationRepository.save(app);

        // 容量 2：前两次放行
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/open/v1/widget/config")
                            .header("X-App-Id", app.getAppKey())
                            .header("Origin", "http://it.example.com"))
                    .andExpect(status().isOk());
        }

        // 第 3 次必须被限流。refillGreedy(2, 1min) 折算约 30s 补 1 个令牌，
        // 紧接着的这次请求不可能补上，故断言稳定。
        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://it.example.com"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void 额度内的请求全部放行() throws Exception {
        Application app = seedApp("ratelimit-ok", "http://it.example.com");
        app.setRateLimitPerMinute(5);
        applicationRepository.save(app);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/open/v1/widget/config")
                            .header("X-App-Id", app.getAppKey())
                            .header("Origin", "http://it.example.com"))
                    .andExpect(status().isOk());
        }
    }
}
