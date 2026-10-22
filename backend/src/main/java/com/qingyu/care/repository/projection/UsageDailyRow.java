package com.qingyu.care.repository.projection;

import java.time.LocalDate;

/**
 * sys_api_usage 按天聚合行。
 *
 * @author ruan
 */
public interface UsageDailyRow {
    LocalDate getDate();
    long getCalls();
    long getTokens();
    long getMessages();
}
