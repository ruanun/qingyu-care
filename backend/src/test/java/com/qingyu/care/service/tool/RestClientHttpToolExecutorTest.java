package com.qingyu.care.service.tool;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * HTTP 工具响应的资源上限（2026-09-30 安全审查 H4）。
 *
 * <p>此前 {@code retrieve().body(String.class)} 把整个响应读进内存，HttpClient 还会自动解 gzip，
 * 截断发生在读完之后——租户建一个指向自己服务器的工具，返回一个 gzip 炸弹或无限字节流，
 * 一次对话就能把整个 JVM 打 OOM、所有租户一起宕。总时长上限由 {@code PinnedHttpClientDeadlineTest}
 * 用真实 socket 覆盖，这里只测读取上限与超时封顶。
 *
 * @author ruan
 */
class RestClientHttpToolExecutorTest {

    /** 永不结束的字节流：模拟无限吐数据的对端（或解压后无穷大的炸弹）。 */
    private static InputStream endless() {
        return new InputStream() {
            @Override
            public int read() {
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 'a');
                return len;
            }
        };
    }

    @Test
    void 无限响应在上限处截断并立即断开连接() {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        String text = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> RestClientHttpToolExecutor.readLimited(endless(), StandardCharsets.UTF_8, client));

        assertThat(text).hasSize(RestClientHttpToolExecutor.MAX_RESPONSE_BYTES);
        // 不断开的话 Spring 关闭响应时会把剩余部分读完，截断形同虚设
        verify(client).close(CloseMode.IMMEDIATE);
    }

    @Test
    void 上限以内的响应原样读回且不断开() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        String text = RestClientHttpToolExecutor.readLimited(
                new ByteArrayInputStream("{\"订单\":\"已发货\"}".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8, client);

        assertThat(text).isEqualTo("{\"订单\":\"已发货\"}");
        verify(client, never()).close(CloseMode.IMMEDIATE);
    }

    @Test
    void 单段超时封顶_租户配不出挂一整天的工具() {
        assertThat(RestClientHttpToolExecutor.effectiveTimeout(Integer.MAX_VALUE))
                .isEqualTo(RestClientHttpToolExecutor.MAX_TIMEOUT_MS);
        assertThat(RestClientHttpToolExecutor.effectiveTimeout(3000)).isEqualTo(3000);
        assertThat(RestClientHttpToolExecutor.effectiveTimeout(0)).isEqualTo(5000);
        assertThat(RestClientHttpToolExecutor.effectiveTimeout(-1)).isEqualTo(5000);
    }
}
