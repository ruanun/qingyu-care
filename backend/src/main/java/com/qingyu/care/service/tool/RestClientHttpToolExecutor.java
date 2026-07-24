package com.qingyu.care.service.tool;

import com.qingyu.care.config.SsrfGuard;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 基于 RestClient + Apache HttpClient 5 的 HTTP 执行器。出站前经 {@link SsrfGuard} 解析并校验目标地址，
 * 并用自定义 {@link DnsResolver} 把「校验期解析出的 IP」钉入连接期，杜绝 SSRF 的 DNS rebinding/TOCTOU：
 * TTL=0 的域名不能再「校验时返回公网 IP、连接时翻转成 169.254.169.254/内网」绕过硬门槛。
 * 主机名在 URL 中原样保留，故 TLS 的 SNI 与证书按主机名校验，不破坏合法 HTTPS。
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

        // 关键：连接期只对该 host 返回上面这批已校验地址，拒绝其它 host（fail-closed）。
        // 这样校验用的解析结果 == 实际连接用的地址，两次解析之间无法翻转 IP。
        DnsResolver pinnedResolver = new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String h) throws UnknownHostException {
                // 只允许目标 host 走已校验的 pinned 地址；任何其它 host（本客户端单次直连、已禁重定向无代理，
                // 理论上不该出现）一律 fail-closed 拒绝，杜绝 URI 解析差异导致的未校验二次解析绕过 SSRF 守卫。
                if (!host.equalsIgnoreCase(h)) {
                    throw new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + h);
                }
                return pinned;
            }
        };

        Timeout timeout = Timeout.ofMilliseconds(timeoutMs);
        // 连接超时在 httpclient5 5.x 已从 RequestConfig 迁至连接管理器的 ConnectionConfig。
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        // 响应（读）超时落 RequestConfig；连接管理器不设最大连接数上限即可（每次调用独立新建、用后即关）。
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build();

        // disableRedirectHandling()：硬关自动跟随 3xx 重定向。重定向目标不再经 SsrfGuard 校验，
        // 且钉住的解析器只覆盖原 host，跳去别的 host 会走真实 DNS，可被 302 指向内网/元数据，故必须禁。
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build()) {

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
