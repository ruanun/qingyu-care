package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 投递消息队列（Redis Stream）。
 *
 * <p>职责：投递请求统一经 {@link #dispatch} 入口——启用队列时 XADD 入流，由
 * {@link WebhookStreamConsumer} 消费组拉取执行；队列关闭或 Redis 故障时回退
 * {@link WebhookSender#deliverAsync} 直投（fail-open，与限流的 Redis 故障策略一致）。
 *
 * <p>语义边界：DB 的投递行（PENDING + claim CAS + 重试扫描）才是事实源与至少一次
 * 保证，队列只负责加速分发与削峰——因此消费侧先 ACK 再投递、消息丢失/重复均无害
 * （丢失由 {@link WebhookRetryScheduler} 扫描兜底，重复被 claim CAS 幂等拦截）。
 *
 * <p>故障熔断：任一 Redis 操作失败进入冷却期（默认 5s），期间入队与轮询直接短路，
 * 避免每次投递都撞连接超时拖慢异步线程池。
 *
 * @author ruan
 */
@Component
@Slf4j
public class WebhookQueue {

    static final String FIELD_DELIVERY_ID = "deliveryId";

    private final StringRedisTemplate redisTemplate;
    private final WebhookProperties properties;
    private final WebhookSender sender;

    /** 消费者名：每实例唯一，消费组按消费者分片投递 */
    private final String consumerName = "consumer-" + UUID.randomUUID();

    /** Redis 故障冷却截止时间戳（毫秒）；冷却期内所有队列操作短路 */
    private volatile long suppressedUntil = 0L;

    /** 消费组是否已确保存在（进程内只需成功创建一次） */
    private volatile boolean groupReady = false;

    public WebhookQueue(StringRedisTemplate redisTemplate,
                        WebhookProperties properties,
                        WebhookSender sender) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.sender = sender;
    }

    /**
     * 投递统一入口：优先入队，队列不可用时直投兜底。
     * 异步执行——调用方（AFTER_COMMIT 的落库线程 / 重试扫描线程）不承担 Redis 连接耗时。
     */
    @Async
    public void dispatch(Long deliveryId) {
        if (!enqueue(deliveryId)) {
            sender.deliverAsync(deliveryId);
        }
    }

    /** 入队一条投递请求；失败（未启用/冷却期/Redis 异常）返回 false 由调用方直投。 */
    boolean enqueue(Long deliveryId) {
        WebhookProperties.Queue q = properties.getQueue();
        if (!q.isEnabled() || inCooldown()) {
            return false;
        }
        try {
            ensureGroup();
            redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(q.getStreamKey())
                    .ofMap(Map.of(FIELD_DELIVERY_ID, String.valueOf(deliveryId))));
            // 近似截断防无界增长；已被消费组读取的旧消息可安全丢弃（DB 重试网兜底）
            redisTemplate.opsForStream().trim(q.getStreamKey(), q.getMaxLength(), true);
            return true;
        } catch (Exception e) {
            enterCooldown(e);
            return false;
        }
    }

    /** 消费组读取一批消息；未启用/冷却期/异常返回空列表。 */
    List<MapRecord<String, Object, Object>> poll() {
        WebhookProperties.Queue q = properties.getQueue();
        if (!q.isEnabled() || inCooldown()) {
            return List.of();
        }
        try {
            ensureGroup();
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(q.getGroup(), consumerName),
                    StreamReadOptions.empty()
                            .count(q.getBatchSize())
                            .block(Duration.ofMillis(q.getBlockMs())),
                    StreamOffset.create(q.getStreamKey(), ReadOffset.lastConsumed()));
            return records == null ? List.of() : records;
        } catch (Exception e) {
            enterCooldown(e);
            return List.of();
        }
    }

    /** ACK 一条消息。失败仅致消息滞留 PEL，投递幂等由 DB claim CAS 保证，降级忽略。 */
    void ack(MapRecord<String, Object, Object> record) {
        try {
            redisTemplate.opsForStream().acknowledge(properties.getQueue().getGroup(), record);
        } catch (Exception e) {
            log.debug("Webhook 队列 ACK 失败（可忽略）: {}", e.getMessage());
        }
    }

    /** 从消息中解析投递行 id；缺失/非法返回 null（调用方跳过）。 */
    Long deliveryIdOf(MapRecord<String, Object, Object> record) {
        Object value = record.getValue().get(FIELD_DELIVERY_ID);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 确保消费组存在（XGROUP CREATE 自带 MKSTREAM；组已存在的 BUSYGROUP 视为成功）。 */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        WebhookProperties.Queue q = properties.getQueue();
        try {
            // 从 0 开始：组创建前已入流的积压消息也能被消费
            redisTemplate.opsForStream().createGroup(q.getStreamKey(), ReadOffset.from("0"), q.getGroup());
        } catch (Exception e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
        groupReady = true;
    }

    private static boolean isBusyGroup(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private boolean inCooldown() {
        return System.currentTimeMillis() < suppressedUntil;
    }

    private void enterCooldown(Exception e) {
        long cooldownMs = properties.getQueue().getFailureCooldownMs();
        // 仅冷却状态切换时告警一次，避免 Redis 长期缺席时刷屏
        if (suppressedUntil < System.currentTimeMillis()) {
            log.warn("Webhook Redis 队列不可用，进入 {}ms 冷却、期间直投兜底: {}", cooldownMs, e.getMessage());
        }
        suppressedUntil = System.currentTimeMillis() + cooldownMs;
    }
}
