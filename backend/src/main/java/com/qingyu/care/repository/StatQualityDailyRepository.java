package com.qingyu.care.repository;

import com.qingyu.care.entity.StatQualityDaily;
import com.qingyu.care.repository.projection.QualityDailyRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 质量日统计查询（tenantId/appId 为 null 时该维度不过滤，同 SysApiUsageRepository 惯例）。
 *
 * @author ruan
 */
@Repository
public interface StatQualityDailyRepository extends JpaRepository<StatQualityDaily, Long> {

    /** 按日聚合（跨应用求和；平均置信度以加权分子 confSum 返回，除法在服务层做） */
    @Query("""
            SELECT q.statDate AS date,
                   SUM(q.aiMsgCount) AS aiMsgCount,
                   SUM(COALESCE(q.avgConfidence, 0) * q.aiMsgCount) AS confSum,
                   SUM(q.lowConfidenceCount) AS lowConfidenceCount,
                   SUM(q.fallbackCount) AS fallbackCount,
                   SUM(q.questionCount) AS questionCount,
                   SUM(q.hitQuestionCount) AS hitQuestionCount,
                   SUM(q.feedbackUp) AS feedbackUp,
                   SUM(q.feedbackDown) AS feedbackDown,
                   SUM(q.sessionCount) AS sessionCount,
                   SUM(q.handoverCount) AS handoverCount,
                   SUM(q.resolvedCount) AS resolvedCount,
                   SUM(q.endedCount) AS endedCount
            FROM StatQualityDaily q
            WHERE q.statDate >= :from
              AND (:tenantId IS NULL OR q.tenantId = :tenantId)
              AND (:appId IS NULL OR q.appId = :appId)
            GROUP BY q.statDate
            ORDER BY q.statDate
            """)
    List<QualityDailyRow> aggregateDaily(@Param("from") LocalDate from,
                                         @Param("tenantId") Long tenantId,
                                         @Param("appId") Long appId);
}
