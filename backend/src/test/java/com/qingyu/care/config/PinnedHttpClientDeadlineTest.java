package com.qingyu.care.config;

import com.qingyu.care.support.DripHttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 出站总时长截止（2026-09-30 安全审查 H3）：用本地「慢速滴灌」服务端证明看门狗真的能打断阻塞读。
 *
 * <p>服务端先回 200 + chunked，然后每 200ms 吐 1 字节——永远比 socket 空闲超时（1s）快，所以
 * {@link PinnedHttpClientFactory#create} 自带的三段超时一个都不会触发，调用本该永远挂着。
 * 只取状态码的写法（Webhook 那样）也一样挂：Spring 关闭响应时会把剩余 body 读完。
 *
 * @author ruan
 */
class PinnedHttpClientDeadlineTest {

    private DripHttpServer server;

    @BeforeEach
    void startDripServer() throws Exception {
        server = new DripHttpServer("text/plain", 200);
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private CloseableHttpClient client() {
        return PinnedHttpClientFactory.create(
                PinnedHttpClientFactory.pinnedResolver("localhost",
                        new InetAddress[]{InetAddress.getLoopbackAddress()}), 1000);
    }

    private String url() {
        return server.url("/hook");
    }

    @Test
    void 只取状态码也会被慢速body挂住_截止时间到点强制中断且判失败() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (CloseableHttpClient http = client()) {
                RestClient rc = RestClient.builder()
                        .requestFactory(new HttpComponentsClientHttpRequestFactory(http)).build();
                long start = System.nanoTime();
                assertThatThrownBy(() -> PinnedHttpClientFactory.callWithDeadline(http, 1500,
                        () -> rc.post().uri(url()).body("{}")
                                .exchange((req, resp) -> resp.getStatusCode().value())))
                        .isInstanceOf(PinnedHttpClientFactory.DeadlineExceededException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            }
        });
    }

    @Test
    void 读body的调用同样被中断() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (CloseableHttpClient http = client()) {
                RestClient rc = RestClient.builder()
                        .requestFactory(new HttpComponentsClientHttpRequestFactory(http)).build();
                assertThatThrownBy(() -> PinnedHttpClientFactory.callWithDeadline(http, 1500,
                        () -> rc.get().uri(url()).retrieve().body(String.class)))
                        .isInstanceOf(PinnedHttpClientFactory.DeadlineExceededException.class);
            }
        });
    }
}
