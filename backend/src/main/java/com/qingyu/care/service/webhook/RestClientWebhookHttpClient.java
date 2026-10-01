package com.qingyu.care.service.webhook;

import com.qingyu.care.config.PinnedHttpClientFactory;
import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.config.WebhookProperties;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.io.CloseMode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
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
 * <p>不读响应体，且整次调用有总时长上限（{@link PinnedHttpClientFactory#callWithDeadline}）。
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
            // 放开私网（集成测试的接收端在 127.0.0.1），但云元数据段永不放行；
            // 只解析一次、钉住校验过的那一份，不能校验完再自己解析（DNS rebinding）
            pinned = SsrfGuard.resolveAllowingPrivate(url);
        } else {
            pinned = SsrfGuard.resolveAndValidate(host);
        }

        try (CloseableHttpClient httpClient = PinnedHttpClientFactory.create(
                PinnedHttpClientFactory.pinnedResolver(host, pinned), properties.getTimeoutMs())) {
            RestClient client = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();
            // 总时长上限 = 单段超时的 2 倍；只要状态码，拿到就立即关掉连接——否则 Spring 关闭响应时会把
            // body 读完，接收端先回 200 再慢速滴灌就能把投递线程永久挂住（2026-09-30 安全审查 H3）
            return PinnedHttpClientFactory.callWithDeadline(httpClient, 2L * properties.getTimeoutMs(),
                    () -> client.post()
                            .uri(url)
                            .headers(h -> headers.forEach(h::set))
                            .body(body)
                            .exchange((request, response) -> {
                                int status = response.getStatusCode().value();
                                httpClient.close(CloseMode.IMMEDIATE);
                                return status;
                            }));
        } catch (IOException e) {
            throw new IllegalStateException("Webhook HTTP 调用失败: " + e.getMessage(), e);
        }
    }
}
