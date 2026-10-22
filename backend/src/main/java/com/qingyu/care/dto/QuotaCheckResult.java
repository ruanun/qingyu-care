package com.qingyu.care.dto;

/**
 * 额度检查结果。limit=0 表示该维度不限。
 *
 * @author ruan
 */
public record QuotaCheckResult(
        boolean exceeded,
        boolean nearThreshold,
        long callsUsed, long callsLimit,
        long tokensUsed, long tokensLimit) {
}
