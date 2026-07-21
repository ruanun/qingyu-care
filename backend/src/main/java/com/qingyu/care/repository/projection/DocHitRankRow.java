package com.qingyu.care.repository.projection;

/**
 * 文档命中排行行。
 *
 * @author ruan
 */
public interface DocHitRankRow {
    Long getDocumentId();
    long getHits();
}
