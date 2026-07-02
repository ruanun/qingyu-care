package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台概览 DTO：系统健康状态 + 五项核心计数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOverviewDTO {

    /** Actuator 健康状态（如 UP / DOWN / OUT_OF_SERVICE） */
    private String health;

    /** 租户总数 */
    private long tenantCount;

    /** 应用总数 */
    private long applicationCount;

    /** 用户总数 */
    private long userCount;

    /** 会话总数 */
    private long sessionCount;

    /** 今日新增会话数 */
    private long todaySessionCount;
}
