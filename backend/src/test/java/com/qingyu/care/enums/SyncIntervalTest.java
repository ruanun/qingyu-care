package com.qingyu.care.enums;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：同步周期枚举的间隔换算与自动调度判定。
 *
 * @author ruan
 */
class SyncIntervalTest {

    @Test
    void 各周期的间隔时长正确() {
        assertThat(SyncInterval.HOURLY.getInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(SyncInterval.DAILY.getInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(SyncInterval.WEEKLY.getInterval()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void MANUAL_不参与自动调度() {
        assertThat(SyncInterval.MANUAL.getInterval()).isNull();
        assertThat(SyncInterval.MANUAL.isAutomatic()).isFalse();
    }

    @Test
    void 其余周期都参与自动调度() {
        assertThat(SyncInterval.HOURLY.isAutomatic()).isTrue();
        assertThat(SyncInterval.DAILY.isAutomatic()).isTrue();
        assertThat(SyncInterval.WEEKLY.isAutomatic()).isTrue();
    }
}
