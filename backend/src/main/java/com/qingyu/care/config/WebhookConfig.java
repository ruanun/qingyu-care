package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Webhook 专用 RestClient（带超时）与定时任务开关。
 *
 * @author ruan
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class WebhookConfig {

    private final WebhookProperties properties;

    @Bean
    public RestClient webhookRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
