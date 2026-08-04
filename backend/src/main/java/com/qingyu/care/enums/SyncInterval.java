package com.qingyu.care.enums;

/**
 * 网页文档的自动同步周期。{@code MANUAL} 表示不参与定时调度、只能手动触发。
 *
 * <p>⚠️ 这里刻意不带 {@code Duration} 字段：真正的到期判据是
 * {@link com.qingyu.care.repository.DocumentRepository#findDueWebPageIds} 里的 SQL
 * 字面量（{@code INTERVAL '1 hour'} 等），判定发生在数据库侧。曾经存在的
 * {@code getInterval()}/{@code isAutomatic()} 全库无人调用，只有它们自己的测试在测，
 * 反而会让人误以为周期语义有测试守护——周期语义的守护在
 * {@code WebPageSyncSchedulerIT.各周期只在超过自身间隔后才到期}。
 *
 * @author ruan
 */
public enum SyncInterval {

    MANUAL,
    HOURLY,
    DAILY,
    WEEKLY
}
