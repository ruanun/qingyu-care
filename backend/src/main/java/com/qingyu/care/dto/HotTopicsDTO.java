package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 热点问题统计返回体。keywords 来自 rollup（截止 statCutoff）；questions 为实时明细（含当日）。
 *
 * @author ruan
 */
public record HotTopicsDTO(
        List<KeywordItem> keywords,
        List<QuestionItem> questions,
        String scope,
        LocalDate statCutoff) {

    /** 关键词项 */
    public record KeywordItem(String keyword, long freq) {
    }

    /** 高频问句项（同内容精确归组） */
    public record QuestionItem(String content, long count) {
    }
}
