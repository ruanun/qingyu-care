package com.qingyu.care.service.webhook;

import com.qingyu.care.config.PinnedHttpClientFactory;
import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.config.WebhookProperties;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * Webhook 投递客户端：与 HTTP 工具同款的出站防线——协议限 http(s)、{@link SsrfGuard} 校验目标
 * 地址、连接期钉住校验期解析出的 IP、硬关重定向、超时。
 *
 * <p>安全审查 H5（2026-09-03）：此前用裸 {@code SimpleClientHttpRequestFactory}（JDK
 * {@code HttpURLConnection}，默认跟随重定向），回调地址任填不校验——租户填
 * {@code http://169.254.169.254/}、{@code http://redis:6379} 即可借平台身份探测内网。
 *
 * <p>{@code allowPrivateTargets} 只跳过网段校验、<b>不跳过钉 IP 与禁重定向</b>：集成测试的接收端
 * 起在 127.0.0.1，本地联调同理；生产必须保持 false。
 *
 * <p>用 {@code exchange()} 拿到状态码而不因非 2xx 抛异常（默认状态处理器被 exchange 绕过）；
 * 传输错误由 RestClient 抛出，交给 {@link WebhookSender} 记失败退避。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class RestClientWebhookHttpClient implements WebhookHttpClient {

    private final WebhookProperties properties;

    @Override
    public int post(String url, Map<String, String> headers, String body) {
        URI uri = SsrfGuard.requireHttpUrl(url);
        String host = uri.getHost();
        InetAddress[] pinned;
        if (properties.isAllowPrivateTargets()) {
            // 放开私网（集成测试的接收端在 127.0.0.1），但云元数据段永不放行
            SsrfGuard.assertAllowedHttpUrlAllowingPrivate(url);
            pinned = resolveWithoutGuard(host);
        } else {
            pinned = SsrfGuard.resolveAndValidate(host);
        }

        try (CloseableHttpClient httpClient = PinnedHttpClientFactory.create(
                PinnedHttpClientFactory.pinnedResolver(host, pinned), properties.getTimeoutMs())) {
            RestClient client = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();
            return client.post()
                    .uri(url)
                    .headers(h -> headers.forEach(h::set))
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (IOException e) {
            throw new IllegalStateException("Webhook HTTP 调用失败: " + e.getMessage(), e);
        }
    }

    private static InetAddress[] resolveWithoutGuard(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("回调地址主机无法解析: " + host, e);
        }
    }
}
