package com.qingyu.care.service;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录防爆破的两层规则（2026-09-25 安全扫描 H1）。Redis 用一个内存 Map 桩出 get / increment / delete。
 *
 * @author ruan
 */
class LoginThrottleServiceTest {

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final Map<String, Long> store = new HashMap<>();
    private LoginThrottleService svc;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(redis);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.get(anyString())).thenAnswer(i -> {
            Long v = store.get(i.<String>getArgument(0));
            return v == null ? null : v.toString();
        });
        lenient().when(ops.increment(anyString())).thenAnswer(i -> store.merge(i.getArgument(0), 1L, Long::sum));
        lenient().when(ops.decrement(anyString())).thenAnswer(i -> store.merge(i.getArgument(0), -1L, Long::sum));
        lenient().when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
        svc = new LoginThrottleService(rateLimitService, provider);
        ReflectionTestUtils.setField(svc, "loginIpLimit", 20);
        ReflectionTestUtils.setField(svc, "registerIpLimit", 5);
        ReflectionTestUtils.setField(svc, "maxFailures", 5);
        ReflectionTestUtils.setField(svc, "lockMinutes", 15);
    }

    @Test
    void IP桶用完时429_登录与注册分桶() {
        when(rateLimitService.tryConsume("auth-login:1.2.3.4", 20)).thenReturn(false);
        when(rateLimitService.tryConsume("auth-register:1.2.3.4", 5)).thenReturn(true);

        assertThatThrownBy(() -> svc.checkLoginIp("1.2.3.4"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(429);
        assertThatCode(() -> svc.checkRegisterIp("1.2.3.4")).doesNotThrowAnyException();
    }

    /** 名额在校验密码之前占用：第 1~5 次放行，第 6 次起拒绝 */
    @Test
    void 连续尝试到上限后锁定_未到上限不锁() {
        for (int i = 0; i < 5; i++) {
            assertThat(svc.acquireAttempt("alice")).isTrue();
        }
        assertThatThrownBy(() -> svc.acquireAttempt("alice"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("15 分钟")
                .extracting("code").isEqualTo(429);
    }

    /**
     * 2026-09-30 安全审查 M4：此前「先 GET 判断 → 校验密码 → 失败再 INCR」，第一次 INCR 落地前并发到达的请求
     * 全都能通过检查。这里模拟 200 个请求在任何一次密码校验结束之前一起到达：只有 5 个拿到名额。
     */
    @Test
    void 并发尝试在任何一次校验结束前到达_也只有上限个能放行() {
        int allowed = 0;
        for (int i = 0; i < 200; i++) {
            try {
                if (svc.acquireAttempt("alice")) allowed++;
            } catch (BusinessException e) {
                // 被锁
            }
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void 计数只在第一次尝试时设过期_到期自动清零() {
        svc.acquireAttempt("alice");
        svc.acquireAttempt("alice");
        verify(redis).expire(eq("auth:login-fail:alice"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void 归还名额后不计入失败() {
        for (int i = 0; i < 5; i++) {
            svc.acquireAttempt("alice");
            svc.releaseAttempt("alice");
        }
        assertThatCode(() -> svc.acquireAttempt("alice")).doesNotThrowAnyException();
    }

    @Test
    void 登录成功清零() {
        for (int i = 0; i < 5; i++) svc.acquireAttempt("alice");
        svc.clearFailures("alice");
        assertThatCode(() -> svc.acquireAttempt("alice")).doesNotThrowAnyException();
    }

    /** 否则换个大小写就能绕过计数 */
    @Test
    void 用户名大小写与首尾空格视为同一账号() {
        for (int i = 0; i < 5; i++) svc.acquireAttempt(i % 2 == 0 ? "Alice" : " alice ");
        assertThatThrownBy(() -> svc.acquireAttempt("ALICE")).isInstanceOf(BusinessException.class);
    }

    @Test
    void Redis故障时放行而不是把人挡在登录页外() {
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(svc.acquireAttempt("alice")).isFalse();
        assertThatCode(() -> svc.acquireAttempt("alice")).doesNotThrowAnyException();
    }

    @Test
    void 上限配成0即关闭() {
        ReflectionTestUtils.setField(svc, "loginIpLimit", 0);
        ReflectionTestUtils.setField(svc, "maxFailures", 0);

        svc.checkLoginIp("1.2.3.4");
        for (int i = 0; i < 10; i++) assertThat(svc.acquireAttempt("alice")).isFalse();

        verify(rateLimitService, never()).tryConsume(anyString(), anyInt());
        verify(ops, never()).increment(anyString());
    }
}
