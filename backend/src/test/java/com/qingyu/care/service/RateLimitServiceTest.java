package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitService 额度解析纯逻辑测试（不连 Redis）
 */
class RateLimitServiceTest {

    @Test
    void resolveLimit_usesAppOverride_whenPresent() {
        Application app = Application.builder().rateLimitPerMinute(120).build();
        assertThat(RateLimitService.resolveLimit(app, 60)).isEqualTo(120);
    }

    @Test
    void resolveLimit_usesDefault_whenAppLimitNull() {
        Application app = Application.builder().rateLimitPerMinute(null).build();
        assertThat(RateLimitService.resolveLimit(app, 60)).isEqualTo(60);
    }

    @Test
    void resolveLimit_usesDefault_whenAppLimitNonPositive() {
        Application app = Application.builder().rateLimitPerMinute(0).build();
        assertThat(RateLimitService.resolveLimit(app, 60)).isEqualTo(60);
    }
}
