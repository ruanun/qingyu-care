package com.qingyu.care.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关。Webhook 出站客户端见 {@code RestClientWebhookHttpClient}（自带 SSRF 防线，不再共享裸 RestClient）。
 *
 * @author ruan
 */
@Configuration
@EnableScheduling
public class WebhookConfig {
}
