package com.qingyu.care.repository;

import com.qingyu.care.entity.StatHotKeywordDaily;
import com.qingyu.care.repository.projection.KeywordFreqRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 热词统计查询。
 *
 * @author ruan
 */
@Repository
public interface StatHotKeywordDailyRepository extends JpaRepository<StatHotKeywordDaily, Long> {

    /** 窗口内按词求和取 TopN（Pageable 控制 N） */
    @Query("""
            SELECT k.keyword AS keyword, SUM(k.freq) AS freq
            FROM StatHotKeywordDaily k
            WHERE k.statDate >= :from
              AND (:tenantId IS NULL OR k.tenantId = :tenantId)
              AND (:appId IS NULL OR k.appId = :appId)
            GROUP BY k.keyword
            ORDER BY SUM(k.freq) DESC
            """)
    List<KeywordFreqRow> topKeywords(@Param("from") LocalDate from,
                                     @Param("tenantId") Long tenantId,
                                     @Param("appId") Long appId,
                                     Pageable pageable);
}
