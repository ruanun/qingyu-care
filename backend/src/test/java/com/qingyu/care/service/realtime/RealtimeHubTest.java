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
}
