package com.qingyu.care.repository.projection;

import java.time.LocalDate;

/**
 * chat_session 按天会话数。
 *
 * @author ruan
 */
public interface SessionDailyRow {
    LocalDate getDate();
    long getSessions();
}
