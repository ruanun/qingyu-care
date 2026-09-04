package com.qingyu.care.config;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PinnedHttpClientFactory}：五条出站路径共用的「钉 IP + 禁重定向 + 超时」HttpClient 构造。
 *
 * <p>安全审查 H5（2026-09-03）：HTTP 工具与网页抓取各自手写了一份同样的构造，Webhook 那条则
 * 干脆没有（裸 {@code SimpleClientHttpRequestFactory}、跟随重定向、不过 SsrfGuard）。安全代码复制
 * 必然漂移，抽成一处让三条路径共用。302 不被跟随由 {@code WebPageFetcherDownloadTest} 用真实
 * 监听器守着，本类只钉解析器的 fail-closed 语义。
 *
 * @author ruan
 */
class PinnedHttpClientFactoryTest {

    @Test
    void 解析器只对目标主机返回钉住的地址且不区分大小写() throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("8.8.8.8")};
        DnsResolver resolver = PinnedHttpClientFactory.pinnedResolver("Example.com", pinned);

        assertThat(resolver.resolve("example.com")).containsExactly(pinned);
        assertThat(resolver.resolve("EXAMPLE.COM")).containsExactly(pinned);
    }

    @Test
    void 解析器对其它主机一律拒绝() throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("8.8.8.8")};
        DnsResolver resolver = PinnedHttpClientFactory.pinnedResolver("example.com", pinned);

        assertThatThrownBy(() -> resolver.resolve("evil.example.net"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void 能按超时构造出客户端() throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("8.8.8.8")};
        try (CloseableHttpClient client = PinnedHttpClientFactory.create(
                PinnedHttpClientFactory.pinnedResolver("example.com", pinned), 1000)) {
            assertThat(client).isNotNull();
        }
    }
}
