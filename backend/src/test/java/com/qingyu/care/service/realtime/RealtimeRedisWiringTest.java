package com.qingyu.care.service.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link RealtimeHub} 与 {@link RealtimeRedisBridge} 的真实 Spring 容器装配回归测试。
 *
 * <p>背景：{@link RealtimeHubTest} 等既有单测全用 {@code new RealtimeHub()} 绕开 Spring 容器，
 * 测不到 bean 缺失场景下的容器装配 bug——此前 {@code RealtimeHub} 用 setter + {@code @Autowired(required
 * = false)} + {@code @Lazy} 注入 {@link RealtimeRedisBridge}：{@code @Lazy} 总会注入一个非 null 懒代理，
 * {@code qingyu.care.realtime.redis.enabled=false} 时目标 bean 不存在，首次 {@code publish} 触发代理解析
 * 便抛 {@code NoSuchBeanDefinitionException}（抛在代理层，绕过 {@link RealtimeRedisBridge#publish}
 * 自身的 try/catch），冒泡击穿 fail-open。本类用 {@link ApplicationContextRunner} 起最小真实容器切片
 * （不需要真 Redis：{@link StringRedisTemplate} 用 mock 装配），复现并守护该场景。
 *
 * @author ruan
 */
class RealtimeRedisWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withUserConfiguration(RealtimeHub.class, RealtimeRedisBridge.class);

    @Test
    void enabled为false时_bridgeBean不装配_hubPublish不抛() {
        runner.withPropertyValues("qingyu.care.realtime.redis.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RealtimeRedisBridge.class);

                    RealtimeHub hub = context.getBean(RealtimeHub.class);
                    // 回归核心断言：bridge bean 缺失时 publish 必须安全退化为纯本地广播，不能抛异常
                    assertThatCode(() -> hub.publish("session:s1", "message", "d"))
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void enabled为true时_bridgeBean装配_hubPublish仍不抛且真实转发到bridge() {
        runner.withPropertyValues("qingyu.care.realtime.redis.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RealtimeRedisBridge.class);

                    RealtimeHub hub = context.getBean(RealtimeHub.class);
                    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
                    assertThatCode(() -> hub.publish("session:s1", "message", "d"))
                            .doesNotThrowAnyException();
                    // ObjectProvider 确实解析到了真实 bridge bean，而非误判缺失退化为纯本地
                    verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq("qingyu:rt:session:s1"),
                            org.mockito.ArgumentMatchers.anyString());
                });
    }
}
