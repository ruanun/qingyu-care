package com.qingyu.care.repository.projection;

/**
 * chat_session 区间汇总。
 *
 * @author ruan
 */
public interface SessionSummaryRow {
    long getTotal();
    long getEnded();
    long getAiResolved();
    long getHandover();
}
