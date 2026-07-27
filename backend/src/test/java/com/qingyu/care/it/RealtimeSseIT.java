package com.qingyu.care.it;

import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.support.AbstractPostgresIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 实时 SSE 端到端（bean 层）：直接注入真实 Spring 上下文中的 {@link RealtimeHub}，验证
 * 订阅（{@code subscribe}）与发布（{@code publish}）在真实容器装配下确实构成多播——
 * 不经 HTTP，避免 MockMvc 对无限流 SSE 端点发起异步请求导致测试进程挂起（RealtimeHub
 * 心跳周期 25s，长连接不会自然结束）。
 *
 * @author ruan
 */
class RealtimeSseIT extends AbstractPostgresIT {

    @Autowired
    private RealtimeHub realtimeHub;

    @Test
    void 订阅后另一线程发布消息事件订阅者应收到() throws Exception {
        String key = "session:realtime-sse-it-" + System.nanoTime();
        // doOnSubscribe 保证发布线程只在 RealtimeHub 内部 channel 已注册（register 先于
        // onSubscribe 信号回传，见 RealtimeHub#subscribe 的 Flux.defer 语义）之后才 publish，
        // 避免"先发布、后订阅"导致 best-effort 丢弃的假阴性。
        CountDownLatch subscribed = new CountDownLatch(1);
        Flux<ServerSentEvent<String>> flux = realtimeHub.subscribe(key)
                .doOnSubscribe(s -> subscribed.countDown());

        ExecutorService publisher = Executors.newSingleThreadExecutor();
        try {
            publisher.submit(() -> {
                try {
                    Assertions.assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
                    // 极小延迟兜底：register() 与 doOnSubscribe 信号之间理论上无窗口，但留一点余量
                    // 防止 CI 环境调度抖动导致 sink 尚未真正 ready for emit。
                    Thread.sleep(50);
                    realtimeHub.publish(key, "message", "{\"text\":\"你好，坐席\"}");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            StepVerifier.create(flux)
                    .assertNext(sse -> {
                        Assertions.assertThat(sse.event()).isEqualTo("message");
                        Assertions.assertThat(sse.data()).isEqualTo("{\"text\":\"你好，坐席\"}");
                    })
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));
        } finally {
            publisher.shutdownNow();
        }
    }

    @Test
    void 无订阅者时发布应安全丢弃不抛异常() {
        String key = "session:realtime-sse-it-empty-" + System.nanoTime();
        Assertions.assertThatCode(() -> realtimeHub.publish(key, "message", "{}"))
                .doesNotThrowAnyException();
        Assertions.assertThat(realtimeHub.isOnline(key)).isFalse();
    }
}
