package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 用量统计看板返回体。scope 供前端切换排行标题/文案（"PLATFORM" 按租户排行 / "TENANT" 按应用排行）。
 *
 * @author ruan
 */
public record UsageAnalyticsDTO(
        Summary summary,
        List<TrendPoint> trend,
        List<RankingItem> ranking,
        String scope) {

    /** 区间汇总。aiResolutionRate / handoverRate 为百分数（0~100，保留一位小数）。 */
    public record Summary(long totalCalls, long totalTokens, long totalMessages,
                          long totalSessions, double aiResolutionRate, double handoverRate) {
    }

    /** 按天趋势点（已补零：区间内每天一个）。 */
    public record TrendPoint(LocalDate date, long calls, long tokens, long sessions) {
    }

    /** 排行项：平台视角 id/name 为租户，租户视角为应用。 */
    public record RankingItem(Long id, String name, long calls, long tokens) {
    }
}
