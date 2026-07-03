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
}
