package com.qingyu.care.service.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时 SSE 传输层：订阅键 → 多播事件流。响应式 Sinks 实现，沿用 OpenChatController 的
 * Flux&lt;ServerSentEvent&gt; SSE 风格。best-effort：无订阅者/发送失败均丢弃，不影响业务
 * （DB 消息为事实源，客户端重连后补拉）。本地内存注册表 + 可选 {@link RealtimeRedisBridge}
 * 跨实例镜像（Redis 不可用或桥接关闭时自动退化为单实例语义）。
 *
 * @author ruan
 */
@Component
@Slf4j
public class RealtimeHub {

    /**
     * 跨实例 Redis 桥接（可为空）：setter + {@code @Lazy} 注入，避免与
     * {@link RealtimeRedisBridge}（构造时依赖本类）形成循环依赖；
     * {@code qingyu.care.realtime.redis.enabled=false} 时无桥接 bean，保持 null，
     * {@link #publish} 退化为纯本地广播。
     */
    private RealtimeRedisBridge bridge;

    @Autowired(required = false)
    public void setBridge(@Lazy RealtimeRedisBridge bridge) {
        this.bridge = bridge;
    }

    /** 每订阅键一个多播 Sink + 活跃订阅者计数（计数归零即摘除，兼作 presence）。 */
    private record Channel(Sinks.Many<ServerSentEvent<String>> sink, AtomicInteger subscribers) {}

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /** 心跳周期：保活长连、探测死连接（注释事件，客户端忽略）。 */
    private static final Duration HEARTBEAT = Duration.ofSeconds(25);

    /**
     * 订阅某键的事件流。多播（同键多标签页共享），断连（cancel/complete）时订阅者计数减一、
     * 归零则摘除该 channel。心跳 merge 进流保活。
     */
    public Flux<ServerSentEvent<String>> subscribe(String key) {
        // Flux.defer：注册（计数+1）推迟到真正被订阅时才发生，与 doFinally 的注销（计数-1）成对，
        // 避免拿到 Flux 却从未订阅时的幽灵 presence
        return Flux.defer(() -> {
            Channel ch = register(key);
            Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT, HEARTBEAT)
                    .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
            return Flux.merge(ch.sink().asFlux(), heartbeat)
                    .doFinally(sig -> deregister(key));
        });
    }

    /** 原子获取或新建 channel 并计数+1（compute 对同一 key 全程串行，杜绝增/减/摘除竞态）。 */
    private Channel register(String key) {
        return channels.compute(key, (k, v) -> {
            Channel c = (v != null) ? v
                    : new Channel(Sinks.many().multicast().directBestEffort(), new AtomicInteger(0));
            c.subscribers().incrementAndGet();
            return c;
        });
    }

    /** 原子计数-1，归零即摘除（computeIfPresent 返回 null 从 map 移除，与 register 同 key 互斥）。 */
    private void deregister(String key) {
        channels.computeIfPresent(key, (k, v) ->
                v.subscribers().decrementAndGet() <= 0 ? null : v);
    }

    /** 发布：本地 Sinks 立即送达 + 镜像到 Redis 供其它实例（bridge 为空/Redis 故障时仅本地，单实例语义）。 */
    public void publish(String key, String event, String data) {
        emitLocal(key, event, data);
        if (bridge != null) {
            bridge.publish(key, event, data);
        }
    }

    /**
     * 仅向本实例的订阅者尽力推送一条命名事件，不镜像到 Redis。无订阅者或发送失败均安全丢弃。
     * 供 {@link #publish} 本地送达，以及 {@link RealtimeRedisBridge} 收到其它实例广播后回灌本地。
     */
    public void emitLocal(String key, String event, String data) {
        Channel ch = channels.get(key);
        if (ch == null) {
            return;
        }
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder(data).event(event).build();
        Sinks.EmitResult r = ch.sink().tryEmitNext(sse);
        if (r.isFailure()) {
            log.debug("实时推送丢弃 key={} event={} result={}", key, event, r);
        }
    }

    /** 该键当前是否有活跃订阅者（presence 判定）。 */
    public boolean isOnline(String key) {
        Channel ch = channels.get(key);
        return ch != null && ch.subscribers().get() > 0;
    }

    /** 枚举当前在线且键以 prefix 开头的订阅键（如某租户在线坐席）。 */
    public List<String> onlineKeys(String prefix) {
        return channels.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getValue().subscribers().get() > 0)
                .map(Map.Entry::getKey)
                .toList();
    }
}
