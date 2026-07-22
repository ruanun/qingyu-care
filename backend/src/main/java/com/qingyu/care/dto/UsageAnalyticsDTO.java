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
        Pricing pricing,
        String scope) {

    /** 区间汇总。aiResolutionRate / handoverRate 为百分数（0~100，保留一位小数）。 */
    public record Summary(long totalCalls, long totalTokens, long totalMessages,
                          long totalSessions, double aiResolutionRate, double handoverRate) {
    }

    /**
     * 成本估算：费率取平台配置 billing.cost.price-per-1k-tokens（元/1K token）。
     * pricePer1kTokens 为 0 表示平台未配置费率（前端提示配置而非显示 ¥0）；
     * totalCostYuan = totalTokens / 1000 × 费率，保留两位小数，属预估口径（用量表无模型维度，
     * 无法按模型差异化计价——若未来需要精确计价须给 sys_api_usage 增加模型维度）。
     */
    public record Pricing(double pricePer1kTokens, double totalCostYuan) {
    }

    /** 按天趋势点（已补零：区间内每天一个）。 */
    public record TrendPoint(LocalDate date, long calls, long tokens, long sessions) {
    }

    /** 排行项：平台视角 id/name 为租户，租户视角为应用。 */
    public record RankingItem(Long id, String name, long calls, long tokens) {
    }
}
