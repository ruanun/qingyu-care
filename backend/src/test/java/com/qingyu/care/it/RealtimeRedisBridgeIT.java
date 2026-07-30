package com.qingyu.care.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeKeys;
import com.qingyu.care.service.realtime.RealtimePresenceRegistry;
import com.qingyu.care.service.realtime.RealtimeRedisBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.codec.ServerSentEvent;
import org.testcontainers.containers.GenericContainer;
import reactor.core.Disposable;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RealtimeHub} 多实例 Redis pub/sub 桥接 + 全局 presence 端到端 IT（真实 Redis 容器）。
 *
 * <p>不继承 {@code AbstractPostgresIT}——本 IT 不需要 Postgres/MockMvc，只需要一个真实 Redis。
 * 采用设计稿 §6 建议的「手工 new 两套 hub+bridge + 两个 RedisMessageListenerContainer 指向同容器」
 * 方案（比起两个独立 Spring 上下文更轻量可控）：每个 {@link #newInstance()} 调用装配一整套独立的
 * {@link LettuceConnectionFactory} + {@link RealtimeHub} + {@link RealtimeRedisBridge} +
 * {@link RedisMessageListenerContainer}（订阅 {@code qingyu:rt:*}，回调仿
 * {@code RealtimeRedisConfig} 生产装配），共享同一个 Redis 容器，模拟两个独立部署实例。
 *
 * <p>关键点：{@code RealtimeHub} 的 {@code bridgeProvider} 字段是 {@code @Autowired}
 * {@link ObjectProvider}，直接 {@code new RealtimeHub()} 时该字段为 null（见既有单测
 * {@code RealtimeHubTest} 的写法），此时 {@code hub.publish()} 只会本地送达、不会镜像到
 * Redis。为了让本 IT 里的 {@code hub.publish()} 真实还原生产环境下"发布会转发到桥接"的行为，
 * {@link #wireBridgeProvider} 用反射把字段设为一个返回本实例 bridge 的 mock
 * {@link ObjectProvider}（等价于 Spring 容器对该字段的真实装配语义，只是绕开容器）。
 *
 * @author ruan
 */
class RealtimeRedisBridgeIT {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    /** 一套"实例"的全部装配：hub + bridge + presence 注册表 + 监听容器 + 各自独立的连接工厂。 */
    private record Instance(RealtimeHub hub, RealtimeRedisBridge bridge, RealtimePresenceRegistry presence,
                             StringRedisTemplate redisTemplate, LettuceConnectionFactory connectionFactory,
                             RedisMessageListenerContainer listenerContainer) {

        void close() {
            try {
                listenerContainer.stop();
                listenerContainer.destroy();
                connectionFactory.destroy();
            } catch (Exception e) {
                // 测试收尾清理失败不阻断其它实例的清理/后续用例
            }
        }
    }

    private final List<Instance> instances = new ArrayList<>();

    @AfterEach
    void tearDown() {
        instances.forEach(Instance::close);
        instances.clear();
    }

    /** 起一套独立"实例"，连同一 Redis 容器（不同的 LettuceConnectionFactory/连接）。 */
    private Instance newInstance() throws Exception {
        LettuceConnectionFactory cf = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        cf.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(cf);

        RealtimeHub hub = new RealtimeHub();
        RealtimeRedisBridge bridge = new RealtimeRedisBridge(redisTemplate, hub, new ObjectMapper());
        wireBridgeProvider(hub, bridge);
        RealtimePresenceRegistry presence = new RealtimePresenceRegistry(redisTemplate, hub);

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        container.addMessageListener(
                (message, pattern) -> bridge.handle(
                        new String(message.getChannel(), StandardCharsets.UTF_8),
                        new String(message.getBody(), StandardCharsets.UTF_8)),
                new PatternTopic(RealtimeRedisBridge.CHANNEL_PREFIX + "*"));
        container.afterPropertiesSet();
        container.start();

        Instance instance = new Instance(hub, bridge, presence, redisTemplate, cf, container);
        instances.add(instance);
        return instance;
    }

    /** 反射注入 RealtimeHub 的私有 bridgeProvider 字段，还原 Spring 容器下的真实装配（见类注释）。 */
    @SuppressWarnings("unchecked")
    private static void wireBridgeProvider(RealtimeHub hub, RealtimeRedisBridge bridge) throws Exception {
        ObjectProvider<RealtimeRedisBridge> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bridge);
        Field field = RealtimeHub.class.getDeclaredField("bridgeProvider");
        field.setAccessible(true);
        field.set(hub, provider);
    }

    @Test
    void 实例A发布会话消息_实例B的订阅者经Redis桥接收到() throws Exception {
        Instance a = newInstance();
        Instance b = newInstance();
        String key = "session:it-bridge-" + System.nanoTime();

        AtomicReference<String> dataOnA = new AtomicReference<>();
        CountDownLatch latchA = new CountDownLatch(1);
        Disposable subA = a.hub().subscribe(key).subscribe(sse -> {
            if (sse.data() != null) {
                dataOnA.set(sse.data());
                latchA.countDown();
            }
        });

        AtomicReference<String> dataOnB = new AtomicReference<>();
        CountDownLatch latchB = new CountDownLatch(1);
        Disposable subB = b.hub().subscribe(key).subscribe(sse -> {
            if (sse.data() != null) {
                dataOnB.set(sse.data());
                latchB.countDown();
            }
        });

        try {
            await().atMost(5, SECONDS).until(() -> a.hub().isOnline(key) && b.hub().isOnline(key));

            // 只从实例 A 发布一次：A 应经本地 emitLocal 立即收到，B 应经 Redis pub/sub 桥接收到
            a.hub().publish(key, "message", "hi-from-a");

            assertThat(latchA.await(5, SECONDS)).as("实例 A 本地送达").isTrue();
            assertThat(latchB.await(10, SECONDS)).as("实例 B 经 Redis 桥接送达").isTrue();
            assertThat(dataOnA.get()).isEqualTo("hi-from-a");
            assertThat(dataOnB.get()).isEqualTo("hi-from-a");
        } finally {
            subA.dispose();
            subB.dispose();
        }
    }

    @Test
    void 租户广播key_双实例订阅坐席都经Redis收到() throws Exception {
        Instance a = newInstance();
        Instance b = newInstance();
        long tenantId = 90_000L + (System.nanoTime() % 100_000);
        String bcastKey = RealtimeKeys.agentBroadcast(tenantId);

        CountDownLatch latchA = new CountDownLatch(1);
        Disposable subA = a.hub().subscribe(bcastKey).subscribe(sse -> {
            if (sse.data() != null) {
                latchA.countDown();
            }
        });
        CountDownLatch latchB = new CountDownLatch(1);
        Disposable subB = b.hub().subscribe(bcastKey).subscribe(sse -> {
            if (sse.data() != null) {
                latchB.countDown();
            }
        });

        try {
            await().atMost(5, SECONDS).until(() -> a.hub().isOnline(bcastKey) && b.hub().isOnline(bcastKey));

            // 从实例 B 发一次广播：B 本地送达，A 经 Redis 桥接送达——两个订阅坐席都应收到
            b.hub().publish(bcastKey, "presence", "{\"agentUserId\":1,\"online\":true}");

            assertThat(latchB.await(5, SECONDS)).as("实例 B 本地送达").isTrue();
            assertThat(latchA.await(10, SECONDS)).as("实例 A 经 Redis 桥接送达").isTrue();
        } finally {
            subA.dispose();
            subB.dispose();
        }
    }

    @Test
    void presence跨实例可见上线下线并剪除僵尸心跳() throws Exception {
        Instance a = newInstance();
        Instance b = newInstance();
        long tenantId = 80_000L + (System.nanoTime() % 100_000);

        // 实例 A 让某坐席上线，实例 B（共享同一 Redis ZSET）应立即可见——presence 是同步 Redis 读写，
        // 不经 pub/sub，无需 await
        a.presence().online(tenantId, 100L);
        assertThat(b.presence().onlineAgents(tenantId)).contains(100L);

        // 实例 B 让该坐席下线，实例 A 应立即看不到
        b.presence().offline(tenantId, 100L);
        assertThat(a.presence().onlineAgents(tenantId)).doesNotContain(100L);

        // 僵尸心跳剪除：直接写入一条早于 RealtimePresenceRegistry.STALE_MS(45000ms) 的陈旧 score
        // （不经 online()，模拟某实例崩溃后未再续期）。presenceKey 前缀是包内可见常量，此处按设计稿
        // §3.3 记录的格式 "qingyu:rt:presence:{tenantId}" 复刻，非跨包访问私有常量。
        String presenceKey = "qingyu:rt:presence:" + tenantId;
        long staleScore = System.currentTimeMillis() - 50_000;
        a.redisTemplate().opsForZSet().add(presenceKey, "999", staleScore);

        List<Long> onlineAfterPrune = b.presence().onlineAgents(tenantId);
        assertThat(onlineAfterPrune).doesNotContain(999L);
        // 不仅查询结果不含，还要确认真的从 Redis 里被剪掉了（而非恰好没取到）
        assertThat(a.redisTemplate().opsForZSet().score(presenceKey, "999")).isNull();
    }
}
