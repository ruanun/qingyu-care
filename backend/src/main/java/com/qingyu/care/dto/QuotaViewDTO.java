package com.qingyu.care.dto;

import java.time.LocalDate;

/**
 * 租户额度视图。plan=null 表示无租户上下文（平台管理员）。
 *
 * @author ruan
 */
public record QuotaViewDTO(
        String plan,
        long callsUsed, long callsLimit,
        long tokensUsed, long tokensLimit,
        int warnThreshold, LocalDate resetDate) {

    public static QuotaViewDTO empty() {
        return new QuotaViewDTO(null, 0, 0, 0, 0, 0, null);
    }
}
