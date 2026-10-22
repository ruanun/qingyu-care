package com.qingyu.care.service.webhook;

import java.util.Map;

/**
 * Webhook HTTP 投递客户端抽象。抽出接口以便 Sender 逻辑用纯 Mockito 单测，
 * 不依赖真实 HTTP。
 *
 * @author ruan
 */
public interface WebhookHttpClient {

    /**
     * POST body（含 headers），返回 HTTP 状态码（含 4xx/5xx，不抛异常）。
     * 传输失败/超时抛 RuntimeException。
     */
    int post(String url, Map<String, String> headers, String body);
}
