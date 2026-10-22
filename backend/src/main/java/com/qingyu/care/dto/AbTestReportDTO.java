package com.qingyu.care.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A/B 实验对比报表：每变体一行，样本量 + 派生率。
 * 率的分母为 0 时为 null；平均置信度无 AI 回复时为 null。
 *
 * @author ruan
 */
public record AbTestReportDTO(
        Long experimentId, String name, String status,
        LocalDateTime startedAt, LocalDateTime endedAt,
        List<VariantReport> variants) {

    public record VariantReport(
            String variantName, Integer weight,
            long sessionCount, long questionCount, long aiMsgCount,
            Double avgConfidence,
            long feedbackUp, long feedbackDown,
            Double likeRate,          // 赞 /(赞+踩)
            Double fallbackRate,      // 兜底 / AI 回复
            Double lowConfidenceRate, // 低置信(<0.5) / AI 回复
            Double hitRate,           // 命中知识 / AI 回复
            Double handoverRate,      // 转人工 / 会话
            Double resolvedRate       // AI 独立解决 / 已结束会话
    ) {}
}
