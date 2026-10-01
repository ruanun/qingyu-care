package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 投递执行器：认领→发送→按结果更新状态/退避重排。
 * 参照 ApiUsageService：不加方法级 @Transactional，各仓库操作各走独立事务
 * （claim 自带事务立即提交、save 自带事务），投递期间不占用数据库事务。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookSender {

    private final WebhookDeliveryRepository deliveryRepository;
    private final ApplicationRepository applicationRepository;
    private final WebhookHttpClient httpClient;
    private final WebhookSignature signature;
    private final WebhookProperties properties;

    /** 异步投递单条：先认领防重复，再发送并落状态。 */
    @Async
    public void deliverAsync(Long deliveryId) {
        if (deliveryRepository.claim(deliveryId) != 1) {
            return; // 已被即时投递或扫描器/其它实例领走
        }
        WebhookDelivery d = deliveryRepository.findById(deliveryId).orElse(null);
        if (d == null) {
            return;
        }
        try {
            Application app = applicationRepository.findById(d.getAppId()).orElse(null);
            // 目标地址在派发时就定格进了投递行。应用已删除、关了 Webhook 或改了回调地址后，还没投完的重试
            // 不该再发：前者会不带签名照发（取不到密钥），后者会把事件推给租户已经弃用的地址（2026-09-30 安全审查）
            if (app == null || app.getWebhookUrl() == null || !app.getWebhookUrl().equals(d.getTargetUrl())) {
                abandon(d, app == null ? "应用已删除，放弃投递" : "回调地址已变更或已关闭，放弃投递");
                deliveryRepository.save(d);
                return;
            }
            int status = send(d.getTargetUrl(), app.getWebhookSecret(), d.getEvent(), String.valueOf(d.getId()), d.getPayload());
            if (status >= 200 && status < 300) {
                d.setStatus(WebhookDeliveryStatus.SUCCESS);
                d.setResponseStatus(status);
                d.setLastError(null);
            } else {
                fail(d, status, "HTTP " + status);
            }
        } catch (Exception e) {
            log.warn("Webhook 投递异常 id={}: {}", deliveryId, e.getMessage());
            fail(d, null, e.getMessage());
        }
        deliveryRepository.save(d);
    }

    /**
     * 同步发送一次，返回 HTTP 状态码（供异步投递与测试端点复用）。
     * 传输失败/超时抛异常，由调用方处理。
     */
    public int send(String url, String secret, String event, String deliveryId, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Qingyu-Event", event);
        headers.put("X-Qingyu-Delivery-Id", deliveryId);
        long timestamp = System.currentTimeMillis();
        headers.put("X-Qingyu-Timestamp", String.valueOf(timestamp));
        if (secret != null && !secret.isBlank()) {
            headers.put("X-Qingyu-Signature", signature.sign(secret, timestamp, body));
        }
        return httpClient.post(url, headers, body);
    }

    /** 失败：attempts++，未耗尽则退避重排为 PENDING，否则 FAILED。 */
    private void fail(WebhookDelivery d, Integer responseStatus, String error) {
        int attempts = d.getAttempts() + 1;
        d.setAttempts(attempts);
        d.setResponseStatus(responseStatus);
        d.setLastError(truncate(error));
        if (attempts < properties.getMaxAttempts()) {
            d.setStatus(WebhookDeliveryStatus.PENDING);
            d.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMinutes(attempts)));
        } else {
            d.setStatus(WebhookDeliveryStatus.FAILED);
        }
    }

    /** 终止投递，不再重试。 */
    private void abandon(WebhookDelivery d, String reason) {
        d.setStatus(WebhookDeliveryStatus.FAILED);
        d.setLastError(reason);
        log.info("Webhook 投递放弃 id={} appId={}: {}", d.getId(), d.getAppId(), reason);
    }

    /** 第 attempts 次失败（从 1 起）对应的退避分钟数。 */
    long backoffMinutes(int attempts) {
        List<Integer> b = properties.getBackoffMinutes();
        int idx = Math.min(Math.max(attempts - 1, 0), b.size() - 1);
        return b.get(idx);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
