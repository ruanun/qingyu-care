package com.qingyu.care.repository.projection;

/**
 * sys_api_usage 排行行；refId 为租户 id 或应用 id。
 *
 * @author ruan
 */
public interface UsageRankRow {
    Long getRefId();
    long getCalls();
    long getTokens();
}
