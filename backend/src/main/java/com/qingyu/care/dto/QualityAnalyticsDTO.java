package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 对话质量看板返回体。statCutoff 为统计截止日（rollup T+1，不含当日）。
 *
 * @author ruan
 */
public record QualityAnalyticsDTO(
        Summary summary,
        List<TrendPoint> trend,
        String scope,
        LocalDate statCutoff) {

    /** 区间汇总。各率值为百分数（0~100，一位小数）；avgConfidence 为 0~1（两位小数）。 */
    public record Summary(long aiMsgCount, double avgConfidence, double lowConfidenceRate,
                          double fallbackRate, long feedbackUp, long feedbackDown,
                          double aiResolutionRate, double handoverRate) {
    }

    /** 按天趋势点（已补零）。 */
    public record TrendPoint(LocalDate date, long aiMsgCount, double avgConfidence,
                             long feedbackUp, long feedbackDown) {
    }
}
