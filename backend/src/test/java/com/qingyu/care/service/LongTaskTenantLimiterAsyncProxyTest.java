package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LongTaskTenantLimiter} 的前提哨兵：经<b>真实的</b> Spring {@code @Async} 代理，返回的 future 要等方法
 * 真正跑完才完成——名额就挂在它上面归还。这个前提一旦不成立（例如被测方法改回返回 void），名额会在任务
 * 开始前就被还掉，限流静默失效而所有手工 future 的单测照样全绿。
 *
 * @author ruan
 */
@SpringJUnitConfig(LongTaskTenantLimiterAsyncProxyTest.Config.class)
@TestPropertySource(properties = "qingyu.care.async.long-task-max-per-tenant=1")
class LongTaskTenantLimiterAsyncProxyTest {

    @Configuration
    @EnableAsync
    static class Config {
        @Bean
        SlowTask slowTask() {
            return new SlowTask();
        }

        @Bean
        LongTaskTenantLimiter limiter() {
            // 上限经属性给：@Bean 方法里 setField 会被随后的 @Value 注入覆盖回默认值
            return new LongTaskTenantLimiter();
        }
    }

    static class SlowTask {
        private final CountDownLatch gate = new CountDownLatch(1);

        /** 经方法而非字段放行：注入进来的是 CGLIB 代理，直接读字段拿到的是代理自己的空字段 */
        public void open() {
            gate.countDown();
        }

        @Async
        public CompletableFuture<Void> run() {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Autowired
    SlowTask slowTask;
    @Autowired
    LongTaskTenantLimiter limiter;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void 名额要等异步任务真正跑完才归还() throws Exception {
        TenantContext.setTenantId(5L);
        limiter.acquire().launch(slowTask::run);

        // 任务还卡在 gate 上：名额必须仍被占着
        Thread.sleep(200);
        assertThatThrownBy(limiter::acquire).isInstanceOf(BusinessException.class);

        slowTask.open();
        for (int i = 0; i < 50 && limiter.runningCount(5L) > 0; i++) {
            Thread.sleep(50);
        }
        assertThat(limiter.runningCount(5L)).isZero();
    }
}
