package com.qingyu.care.service.tool;

import com.qingyu.care.config.SsrfGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 基于 RestClient 的 HTTP 执行器。出站前经 {@link SsrfGuard} 校验目标地址。
 *
 * @author ruan
 */
@Component
public class RestClientHttpToolExecutor implements HttpToolExecutor {

    @Override
    public String execute(String method, String url, String authHeader, String body, int timeoutMs) {
        SsrfGuard.assertAllowed(url);

        // 禁止底层自动跟随 3xx 重定向：SimpleClientHttpRequestFactory 默认对 GET 跟随重定向，
        // 会绕过上面的 SsrfGuard（重定向目标不再校验），可被 302 指向内网/元数据，故此处硬关。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient client = RestClient.builder().requestFactory(factory).build();

        RestClient.RequestBodySpec spec = client
                .method("POST".equalsIgnoreCase(method) ? org.springframework.http.HttpMethod.POST
                        : org.springframework.http.HttpMethod.GET)
                .uri(url);
        applyAuthHeader(spec, authHeader);
        if ("POST".equalsIgnoreCase(method) && body != null && !body.isBlank()) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        String resp = spec.retrieve().body(String.class);
        return resp == null ? "" : resp;
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
