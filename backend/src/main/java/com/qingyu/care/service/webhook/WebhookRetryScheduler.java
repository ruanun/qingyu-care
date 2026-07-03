package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时扫描到期待重试的投递并交给 WebhookSender。
 * 首投失败后的兜底；间隔由 qingyu.care.webhook.retry-scan-interval-ms 控制。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookRetryScheduler {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSender sender;

    @Scheduled(fixedDelayString = "${qingyu.care.webhook.retry-scan-interval-ms:30000}")
    public void scanAndRetry() {
        List<WebhookDelivery> due = deliveryRepository.findByStatusAndNextAttemptAtLessThanEqual(
                WebhookDeliveryStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, 100));
        if (due.isEmpty()) {
            return;
        }
        log.debug("Webhook 重试扫描：{} 条待投递", due.size());
        for (WebhookDelivery d : due) {
            sender.deliverAsync(d.getId());
        }
    }
}
