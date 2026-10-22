package com.qingyu.care.repository.projection;

/**
 * 租户当月用量累计。
 * 返回值 {@code long} 依赖 Hibernate 6 + pg17 对 {@code bigint} 聚合的自动拆箱；更换 DB 方言时需复测。
 *
 * @author ruan
 */
public interface QuotaUsageRow {
    long getCalls();
    long getTokens();
}
