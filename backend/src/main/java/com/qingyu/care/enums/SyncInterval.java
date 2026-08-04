package com.qingyu.care.enums;

import java.time.Duration;

/**
 * 网页文档的自动同步周期。{@code MANUAL} 表示不参与定时调度、只能手动触发。
 *
 * @author ruan
 */
public enum SyncInterval {

    MANUAL(null),
    HOURLY(Duration.ofHours(1)),
    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7));

    private final Duration interval;

    SyncInterval(Duration interval) {
        this.interval = interval;
    }

    /** 自动同步间隔；MANUAL 返回 null。 */
    public Duration getInterval() {
        return interval;
    }

    /** 是否参与定时调度。 */
    public boolean isAutomatic() {
        return interval != null;
    }
}
