package com.qingyu.care.service;

import com.qingyu.care.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * 登录 / 注册的防爆破（2026-09-25 安全扫描 H1：两个端点此前没有任何限流，可无限次撞库）。
 *
 * <p>两层，各防一种打法：
 * <ul>
 *   <li><b>按 IP 限流</b>：挡单一来源的高频尝试。复用 {@link RateLimitService}（Bucket4j + Redis）。</li>
 *   <li><b>按用户名锁定</b>：挡「换很多 IP 猜同一个账号」——按 IP 限流对它无效。连续失败到上限后锁定一段时间，
 *       计数从第一次失败起算、到期自动清零，登录成功即清零。</li>
 * </ul>
 * 不存在的用户名同样计数：否则「锁定」与「不锁定」的差异本身就能用来探测哪些用户名存在。
 *
 * <p>⚠️ 已知代价：知道某人用户名的人可以故意输错把他锁住。这是按账号锁定的通病，锁定期取得短（默认 15 分钟）
 * 就是为了把这个代价压小；真要根治得上验证码，而系统目前没有这条通道。
 *
 * <p>Redis 不可用时 fail-open（放行 + WARN），与 {@link RateLimitService} 同口径：宁可暂时失去防爆破，
 * 也不能因为 Redis 故障把所有人挡在登录页外。
 *
 * @author ruan
 */
@Slf4j
@Service
public class LoginThrottleService {

    private static final String FAIL_KEY_PREFIX = "auth:login-fail:";

    private final RateLimitService rateLimitService;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** 每个 IP 每分钟最多尝试登录的次数；0 = 关闭 */
    @Value("${qingyu.care.auth.login-ip-limit-per-minute:20}")
    private int loginIpLimit;

    /** 每个 IP 每分钟最多注册的次数；0 = 关闭 */
    @Value("${qingyu.care.auth.register-ip-limit-per-minute:5}")
    private int registerIpLimit;

    /** 同一用户名连续失败多少次后锁定；0 = 关闭 */
    @Value("${qingyu.care.auth.login-max-failures:5}")
    private int maxFailures;

    @Value("${qingyu.care.auth.login-lock-minutes:15}")
    private int lockMinutes;

    public LoginThrottleService(RateLimitService rateLimitService, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.rateLimitService = rateLimitService;
        this.redisProvider = redisProvider;
    }

    public void checkLoginIp(String clientIp) {
        checkIp("auth-login:", clientIp, loginIpLimit);
    }

    public void checkRegisterIp(String clientIp) {
        checkIp("auth-register:", clientIp, registerIpLimit);
    }

    private void checkIp(String prefix, String clientIp, int limit) {
        if (limit <= 0 || clientIp == null || clientIp.isBlank()) {
            return;
        }
        if (!rateLimitService.tryConsume(prefix + clientIp, limit)) {
            throw new BusinessException(429, "操作过于频繁，请 1 分钟后再试");
        }
    }

    /** 已锁定则抛 429。在校验密码之前调用：锁定期内连正确密码也不放行，否则锁定形同虚设。 */
    public void assertNotLocked(String username) {
        if (maxFailures <= 0 || username == null) {
            return;
        }
        String count = redis(r -> r.opsForValue().get(failKey(username)), null);
        if (count != null && Long.parseLong(count) >= maxFailures) {
            throw new BusinessException(429, "密码错误次数过多，账号已临时锁定，请 " + lockMinutes + " 分钟后再试");
        }
    }

    public void recordFailure(String username) {
        if (maxFailures <= 0 || username == null) {
            return;
        }
        String key = failKey(username);
        redis(r -> {
            Long n = r.opsForValue().increment(key);
            if (n != null && n == 1L) {
                r.expire(key, Duration.ofMinutes(lockMinutes));
            }
            return n;
        }, null);
    }

    public void clearFailures(String username) {
        if (username == null) {
            return;
        }
        redis(r -> r.delete(failKey(username)), null);
    }

    /** 用户名大小写不同算同一个账号，否则换个大小写就绕过了计数 */
    private static String failKey(String username) {
        return FAIL_KEY_PREFIX + username.trim().toLowerCase(Locale.ROOT);
    }

    private <T> T redis(java.util.function.Function<StringRedisTemplate, T> op, T fallback) {
        StringRedisTemplate r = redisProvider.getIfAvailable();
        if (r == null) {
            return fallback;
        }
        try {
            return op.apply(r);
        } catch (Exception e) {
            log.warn("登录失败计数不可用，fail-open 放行: {}", e.getMessage());
            return fallback;
        }
    }
}
