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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import com.qingyu.care.util.AfterCommit;

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
    private final WebhookQueue queue;

    // AFTER_COMMIT 阶段原事务已提交，此处的落库必须开启独立新事务（REQUIRES_NEW），
    // 否则会并入已完成的事务而不再提交——PENDING 行拿不到 id、也不落库，异步投递认领不到（历史 bug）。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        // 首投走队列（Redis Stream；队列关闭或故障时内部回退直投）。
        // 经 AfterCommit 推迟到本 REQUIRES_NEW 事务提交之后再投递：dispatch 是 @Async，
        // 消费侧拿 deliveryId 去 findById，在提交前投递时可能读不到这一行。
        // 那样并不会丢事件（WebhookRetryScheduler 会重扫 PENDING 行，DB 投递行才是事实源、
        // 队列只是加速通道），但白等一个重试周期没有必要。
        final Long deliveryId = delivery.getId();
        AfterCommit.run(() -> queue.dispatch(deliveryId));
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
