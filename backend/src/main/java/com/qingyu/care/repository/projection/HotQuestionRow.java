package com.qingyu.care.repository.projection;

/**
 * 高频问句行（实时明细，同内容精确归组）。
 *
 * @author ruan
 */
public interface HotQuestionRow {
    String getContent();
    long getCnt();
}
