package com.qingyu.care.repository.projection;

import java.time.LocalDate;

/**
 * 质量日统计聚合行（跨应用求和；confSum = Σ(avg_confidence × ai_msg_count)，加权分子，除法在服务层做）。
 *
 * @author ruan
 */
public interface QualityDailyRow {
    LocalDate getDate();
    long getAiMsgCount();
    Double getConfSum();
    long getLowConfidenceCount();
    long getFallbackCount();
    long getQuestionCount();
    long getHitQuestionCount();
    long getFeedbackUp();
    long getFeedbackDown();
    long getSessionCount();
    long getHandoverCount();
    long getResolvedCount();
    long getEndedCount();
}
