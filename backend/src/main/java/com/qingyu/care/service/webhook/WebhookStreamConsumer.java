package com.qingyu.care.service.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Webhook 队列消费者：轮询 Redis Stream 消费组，把投递请求交给 {@link WebhookSender}。
 *
 * <p>先 ACK 再投递：队列只做分发加速，至少一次语义由 DB 投递行 + 重试扫描兜底，
 * 重复消息被 claim CAS 幂等拦截，故无需消费失败重回队列（避免 PEL 无限膨胀）。
 * 队列未启用或 Redis 冷却期时 {@link WebhookQueue#poll} 返回空，本任务空转无副作用。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookStreamConsumer {

    private final WebhookQueue queue;
    private final WebhookSender sender;

    @Scheduled(fixedDelayString = "${qingyu.care.webhook.queue.poll-interval-ms:200}")
    public void pollAndDeliver() {
        for (MapRecord<String, Object, Object> record : queue.poll()) {
            queue.ack(record);
            Long deliveryId = queue.deliveryIdOf(record);
            if (deliveryId != null) {
                sender.deliverAsync(deliveryId);
            } else {
                log.warn("Webhook 队列消息缺少合法 deliveryId，已跳过: {}", record.getId());
            }
        }
    }
}
