package com.qingyu.care.service.tool;

import com.qingyu.care.config.PinnedHttpClientFactory;
import com.qingyu.care.config.SsrfGuard;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

/**
 * 基于 RestClient + Apache HttpClient 5 的 HTTP 执行器。出站前经 {@link SsrfGuard} 解析并校验目标地址，
 * 并经 {@link PinnedHttpClientFactory} 把「校验期解析出的 IP」钉入连接期，杜绝 SSRF 的 DNS rebinding/TOCTOU：
 * TTL=0 的域名不能再「校验时返回公网 IP、连接时翻转成 169.254.169.254/内网」绕过硬门槛。
 * 主机名在 URL 中原样保留，故 TLS 的 SNI 与证书按主机名校验，不破坏合法 HTTPS。
 * 重定向跟随由工厂硬关：重定向目标不再经 SsrfGuard 校验，跳去别的 host 会走真实 DNS。
 *
 * @author ruan
 */
@Component
public class RestClientHttpToolExecutor implements HttpToolExecutor {

    @Override
    public String execute(String method, String url, String authHeader, String body, int timeoutMs) {
        // 出站前解析+校验目标主机一次，拿到「已校验」的全部地址，供连接期复用。
        final String host = URI.create(url).getHost();
        final InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        try (CloseableHttpClient httpClient = PinnedHttpClientFactory.create(
                PinnedHttpClientFactory.pinnedResolver(host, pinned), timeoutMs)) {

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            RestClient client = RestClient.builder().requestFactory(factory).build();

            RestClient.RequestBodySpec spec = client
                    .method("POST".equalsIgnoreCase(method) ? HttpMethod.POST : HttpMethod.GET)
                    .uri(url);
            applyAuthHeader(spec, authHeader);
            if ("POST".equalsIgnoreCase(method) && body != null && !body.isBlank()) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            String resp = spec.retrieve().body(String.class);
            return resp == null ? "" : resp;
        } catch (IOException e) {
            throw new IllegalStateException("工具 HTTP 调用失败: " + e.getMessage(), e);
        }
    }

    /** 把 "Header-Name: value" 整行头拆成键值加到请求上。 */
    private void applyAuthHeader(RestClient.RequestBodySpec spec, String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return;
        }
        int idx = authHeader.indexOf(':');
        if (idx > 0) {
            spec.header(authHeader.substring(0, idx).trim(), authHeader.substring(idx + 1).trim());
        } else {
            spec.header(HttpHeaders.AUTHORIZATION, authHeader.trim());
        }
    }
}
