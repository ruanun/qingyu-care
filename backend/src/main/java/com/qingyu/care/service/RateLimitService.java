package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 基于 Bucket4j + Redis 的按 appKey 分布式限流。
 *
 * <p>启动健壮性：proxyManager 通过 {@link ObjectProvider} 懒加载注入，
 * 构造函数不触发 Redis 连接；{@link #tryConsume} 全面 catch 所有异常，
 * Redis 故障时 fail-open（放行 + log.warn），不崩溃、不拦截。
 */
@Service
@Slf4j
public class RateLimitService {

    /**
     * 使用 ObjectProvider 懒加载，避免构造时触发 @Lazy bean 初始化（即 Redis 连接）。
     * 只有在 tryConsume 真正调用时才通过 getObject() 获取 ProxyManager 实例。
     */
    private final ObjectProvider<ProxyManager<String>> proxyManagerProvider;

    @Autowired
    public RateLimitService(@Lazy ObjectProvider<ProxyManager<String>> proxyManagerProvider) {
        this.proxyManagerProvider = proxyManagerProvider;
    }

    /**
     * 解析生效额度：应用级覆盖优先，非正值或为空时回落到全局默认。
     *
     * @param app          应用实体
     * @param defaultLimit 平台默认每分钟限额
     * @return 实际生效的每分钟请求上限
     */
    public static int resolveLimit(Application app, int defaultLimit) {
        Integer override = app.getRateLimitPerMinute();
        return (override != null && override > 0) ? override : defaultLimit;
    }

    /**
     * 消费一个令牌；被限流返回 false。Redis 任何异常时放行（返回 true）。
     *
     * @param appKey        应用标识，用作 Redis key 前缀
     * @param limitPerMinute 每分钟令牌上限
     * @return true=放行，false=被限流
     */
    public boolean tryConsume(String appKey, int limitPerMinute) {
        try {
            ProxyManager<String> proxyManager = proxyManagerProvider.getObject();
            Supplier<BucketConfiguration> cfg = () -> BucketConfiguration.builder()
                    .addLimit(limit -> limit
                            .capacity(limitPerMinute)
                            .refillGreedy(limitPerMinute, Duration.ofMinutes(1)))
                    .build();
            return proxyManager.getProxy("ratelimit:" + appKey, cfg).tryConsume(1);
        } catch (Exception e) {
            // 末参传异常对象，SLF4J 会附加堆栈，便于区分 Redis 网络错误与编程错误
            log.warn("限流组件异常，fail-open 放行本次请求 appKey={}", appKey, e);
            return true;
        }
    }
}
