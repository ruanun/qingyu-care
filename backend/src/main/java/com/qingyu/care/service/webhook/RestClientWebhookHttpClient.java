package com.qingyu.care.service.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 基于 Spring RestClient 的 Webhook 投递实现。用 exchange() 拿到状态码而不因
 * 非 2xx 抛异常（默认状态处理器被 exchange 绕过）；传输错误由 RestClient 抛出。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class RestClientWebhookHttpClient implements WebhookHttpClient {

    private final RestClient webhookRestClient;

    @Override
    public int post(String url, Map<String, String> headers, String body) {
        return webhookRestClient.post()
                .uri(url)
                .headers(h -> headers.forEach(h::set))
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
