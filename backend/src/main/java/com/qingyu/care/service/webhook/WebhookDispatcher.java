package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 监听领域事件（事务提交后），过滤订阅、落投递行并触发异步首投。
 * 用 AFTER_COMMIT 确保只推送已提交状态；异步 send 由 WebhookSender 完成，
 * 此处只做落库，保持轻量。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcher {

    private final ApplicationRepository applicationRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookPayloadBuilder payloadBuilder;
    private final WebhookSender sender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(WebhookDomainEvent event) {
        if (event.getAppId() == null) {
            return; // 内部会话无 appId，不推送
        }
        Application app = applicationRepository.findById(event.getAppId()).orElse(null);
        if (app == null || app.getWebhookUrl() == null || app.getWebhookUrl().isBlank()) {
            return;
        }
        if (!isSubscribed(app.getWebhookEvents(), event.getEventType())) {
            return;
        }

        // 先落 PENDING 行拿到 id（兼作 Delivery-Id 与 payload.id）
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.builder()
                .tenantId(event.getTenantId())
                .appId(event.getAppId())
                .event(event.getEventType())
                .targetUrl(app.getWebhookUrl())
                .status(WebhookDeliveryStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(event.getOccurredAt())
                .build());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", event.getSessionId());
        data.put("appId", event.getAppId());
        if (event.getTrigger() != null) {
            data.put("trigger", event.getTrigger());
        }
        delivery.setPayload(payloadBuilder.build(
                String.valueOf(delivery.getId()), event.getEventType(), event.getOccurredAt(), data));
        deliveryRepository.save(delivery);

        sender.deliverAsync(delivery.getId());
    }

    /** 逗号拆分、trim 后精确匹配事件类型。 */
    private boolean isSubscribed(String subscribed, String eventType) {
        if (subscribed == null || subscribed.isBlank()) {
            return false;
        }
        for (String s : subscribed.split(",")) {
            if (s.trim().equals(eventType)) {
                return true;
            }
        }
        return false;
    }
}
