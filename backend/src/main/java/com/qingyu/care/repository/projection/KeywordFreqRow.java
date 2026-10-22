package com.qingyu.care.repository.projection;

/**
 * 热词聚合行。
 *
 * @author ruan
 */
public interface KeywordFreqRow {
    String getKeyword();
    long getFreq();
}
