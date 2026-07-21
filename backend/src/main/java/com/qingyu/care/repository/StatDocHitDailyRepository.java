package com.qingyu.care.repository;

import com.qingyu.care.entity.StatDocHitDaily;
import com.qingyu.care.repository.projection.DocHitRankRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 文档命中统计查询。
 *
 * @author ruan
 */
@Repository
public interface StatDocHitDailyRepository extends JpaRepository<StatDocHitDaily, Long> {

    /** 窗口内文档命中排行 TopN */
    @Query("""
            SELECT d.documentId AS documentId, SUM(d.hitCount) AS hits
            FROM StatDocHitDaily d
            WHERE d.statDate >= :from
              AND (:tenantId IS NULL OR d.tenantId = :tenantId)
              AND (:appId IS NULL OR d.appId = :appId)
            GROUP BY d.documentId
            ORDER BY SUM(d.hitCount) DESC
            """)
    List<DocHitRankRow> rankDocs(@Param("from") LocalDate from,
                                 @Param("tenantId") Long tenantId,
                                 @Param("appId") Long appId,
                                 Pageable pageable);
}
