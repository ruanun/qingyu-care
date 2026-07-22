package com.qingyu.care.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook 投递配置（qingyu.care.webhook.*）。
 *
 * @author ruan
 */
@Component
@ConfigurationProperties(prefix = "qingyu.care.webhook")
@Data
public class WebhookProperties {

    /** 最大投递尝试次数（含首次）。 */
    private int maxAttempts = 5;

    /** HTTP 连接/读取超时（毫秒）。 */
    private int timeoutMs = 5000;

    /** 重试扫描间隔（毫秒）。 */
    private long retryScanIntervalMs = 30000;

    /** 各次失败后的退避分钟数（第 n 次失败取 index min(n-1, size-1)）。 */
    private List<Integer> backoffMinutes = List.of(1, 5, 15, 30, 60);

    /** Redis Stream 队列投递（qingyu.care.webhook.queue.*）。 */
    private Queue queue = new Queue();

    /**
     * 队列层配置。启用时投递请求先 XADD 入流、由消费组拉取执行；
     * 关闭或 Redis 故障（冷却期）时回退 @Async 直投，DB 重试扫描始终兜底。
     */
    @Data
    public static class Queue {

        /** 是否启用 Redis Stream 队列投递。 */
        private boolean enabled = true;

        /** Stream 键名。 */
        private String streamKey = "qingyu:webhook:deliveries";

        /** 消费组名。 */
        private String group = "webhook-workers";

        /** 消费轮询间隔（毫秒，fixedDelay）。 */
        private long pollIntervalMs = 200;

        /** 单次 XREADGROUP 批量条数。 */
        private int batchSize = 16;

        /** XREADGROUP 阻塞等待时长（毫秒）。 */
        private long blockMs = 1000;

        /** 流近似截断长度（XTRIM ~，防无界增长）。 */
        private long maxLength = 10000;

        /** Redis 故障后的冷却时长（毫秒）：冷却期内跳过队列、直接直投兜底。 */
        private long failureCooldownMs = 5000;
    }
}
