package com.qingyu.care.service.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeHubTest {

    @Test
    void 订阅者收到发布的命名事件() {
        RealtimeHub hub = new RealtimeHub();
        Flux<ServerSentEvent<String>> stream = hub.subscribe("k1");

        StepVerifier.create(stream)
                .then(() -> hub.publish("k1", "message", "{\"x\":1}"))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("message");
                    assertThat(sse.data()).isEqualTo("{\"x\":1}");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void 发布到无订阅者的键_安全丢弃不抛异常() {
        RealtimeHub hub = new RealtimeHub();
        hub.publish("nobody", "message", "{}");   // 不应抛
        assertThat(hub.isOnline("nobody")).isFalse();
    }

    @Test
    void 有活跃订阅者时isOnline为真_取消后为假() {
        RealtimeHub hub = new RealtimeHub();
        assertThat(hub.isOnline("k2")).isFalse();
        reactor.core.Disposable d = hub.subscribe("k2").subscribe();
        assertThat(hub.isOnline("k2")).isTrue();
        d.dispose();
        assertThat(hub.isOnline("k2")).isFalse();
    }

    @Test
    void onlineKeys按前缀枚举在线键() {
        RealtimeHub hub = new RealtimeHub();
        reactor.core.Disposable a = hub.subscribe("agent:9:1").subscribe();
        reactor.core.Disposable b = hub.subscribe("agent:9:2").subscribe();
        reactor.core.Disposable c = hub.subscribe("agent:8:1").subscribe();
        assertThat(hub.onlineKeys("agent:9:")).containsExactlyInAnyOrder("agent:9:1", "agent:9:2");
        a.dispose(); b.dispose(); c.dispose();
    }

    @Test
    void 同一key多个订阅者都收到同一次广播() {
        RealtimeHub hub = new RealtimeHub();
        java.util.List<String> a = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> b = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactor.core.Disposable d1 = hub.subscribe("k").subscribe(sse -> { if (sse.data() != null) a.add(sse.data()); });
        reactor.core.Disposable d2 = hub.subscribe("k").subscribe(sse -> { if (sse.data() != null) b.add(sse.data()); });
        // 两个订阅就绪后再发（directBestEffort 无缓冲，发早了会丢）
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                .until(() -> hub.isOnline("k"));
        hub.publish("k", "message", "hi");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1))
                .until(() -> !a.isEmpty() && !b.isEmpty());
        d1.dispose();
        d2.dispose();
        assertThat(a).containsExactly("hi");
        assertThat(b).containsExactly("hi");
    }
}
