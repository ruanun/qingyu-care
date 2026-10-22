package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 知识覆盖率返回体。answeredCount = AI 回答数（每条对应一个提问）；hitRate 为百分数。
 *
 * @author ruan
 */
public record CoverageAnalyticsDTO(
        Summary summary,
        List<TrendPoint> trend,
        List<DocHitItem> topDocs,
        List<DocItem> zeroHitDocs,
        String scope,
        LocalDate statCutoff) {

    public record Summary(long answeredCount, long hitCount, double hitRate) {
    }

    public record TrendPoint(LocalDate date, long answeredCount, long hitCount, double hitRate) {
    }

    /** 文档命中排行项 */
    public record DocHitItem(Long documentId, String name, long hits) {
    }

    /** 零命中文档项 */
    public record DocItem(Long documentId, String name) {
    }
}
