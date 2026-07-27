package com.qingyu.care.service.realtime;

import lombok.extern.slf4j.Slf4j;
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
 * （DB 消息为事实源，客户端重连后补拉）。单实例内存注册表，多实例广播留后续。
 *
 * @author ruan
 */
@Component
@Slf4j
public class RealtimeHub {

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
        Channel ch = channels.computeIfAbsent(key,
                k -> new Channel(Sinks.many().multicast().directBestEffort(), new AtomicInteger(0)));
        ch.subscribers().incrementAndGet();
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT, HEARTBEAT)
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        return Flux.merge(ch.sink().asFlux(), heartbeat)
                .doFinally(sig -> {
                    if (ch.subscribers().decrementAndGet() <= 0) {
                        channels.remove(key, ch);
                    }
                });
    }

    /** 向某键的订阅者尽力推送一条命名事件。无订阅者或发送失败均安全丢弃。 */
    public void publish(String key, String event, String data) {
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
