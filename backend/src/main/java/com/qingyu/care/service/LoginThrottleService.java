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
 *       计数从第一次失败起算、到期自动清零，登录成功即清零。名额在校验密码前原子占用，见 {@link #acquireAttempt}。</li>
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

    /** 每个 IP 每分钟最多获取图形验证码的次数；0 = 关闭 */
    @Value("${qingyu.care.auth.captcha-ip-limit-per-minute:30}")
    private int captchaIpLimit;

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

    public void checkCaptchaIp(String clientIp) {
        checkIp("auth-captcha:", clientIp, captchaIpLimit);
    }

    private void checkIp(String prefix, String clientIp, int limit) {
        if (limit <= 0 || clientIp == null || clientIp.isBlank()) {
            return;
        }
        if (!rateLimitService.tryConsume(prefix + clientIp, limit)) {
            throw new BusinessException(429, "操作过于频繁，请 1 分钟后再试");
        }
    }

    /**
     * 在校验密码<b>之前</b>原子地占一次尝试名额：先 INCR，结果超过上限即抛 429。锁定期内连正确密码也不放行。
     *
     * <p>⚠️ 2026-09-30 安全审查 M4：此前是「先 GET 判断是否锁定 → 校验密码（BCrypt 约 50~100ms）→ 失败再 INCR」，
     * 第一次 INCR 落地之前并发到达的请求全都能通过检查——同时发 200 个不同密码，200 次都真的被校验了，之后才锁。
     * 先占名额再校验，并发里最多只有上限个能走到 BCrypt。
     *
     * @return 是否真的计了数。Redis 不可用（fail-open）或功能关闭时为 false，调用方据此决定要不要 {@link #releaseAttempt}
     */
    public boolean acquireAttempt(String username) {
        if (maxFailures <= 0 || username == null) {
            return false;
        }
        String key = failKey(username);
        Long n = redis(r -> {
            Long v = r.opsForValue().increment(key);
            if (v != null && v == 1L) {
                // 锁定窗口从第一次尝试起算，到期自动清零
                r.expire(key, Duration.ofMinutes(lockMinutes));
            }
            return v;
        }, null);
        if (n == null) {
            return false;
        }
        if (n > maxFailures) {
            throw new BusinessException(429, "密码错误次数过多，账号已临时锁定，请 " + lockMinutes + " 分钟后再试");
        }
        return true;
    }

    /**
     * 归还名额：只有凭据错才算失败。账号停用 / 租户冻结时密码其实是对的，计进去会把人锁在一个本就进不去的门外；
     * 其它异常（系统故障）同理不该算到用户头上。
     */
    public void releaseAttempt(String username) {
        if (username == null) {
            return;
        }
        redis(r -> r.opsForValue().decrement(failKey(username)), null);
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
