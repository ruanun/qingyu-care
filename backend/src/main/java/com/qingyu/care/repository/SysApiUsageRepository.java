package com.qingyu.care.repository;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.projection.UsageDailyRow;
import com.qingyu.care.repository.projection.UsageRankRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 开放 API 用量仓库：原子累加 + 查询。
 *
 * @author ruan
 */
@Repository
public interface SysApiUsageRepository extends JpaRepository<SysApiUsage, Long> {

    /**
     * 原子累加当天用量，返回受影响行数（0 表示当天尚无记录，需 insert）。
     * {@code @Modifying} 更新必须在事务中执行；调用方 ApiUsageService.record 刻意不加
     * 方法级事务（避免唯一键冲突把整方法标记 rollback-only），故在此让本方法各走独立事务。
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE sys_api_usage SET call_count = call_count + 1, " +
            "message_count = message_count + :messages, token_count = token_count + :tokens, " +
            "updated_at = CURRENT_TIMESTAMP WHERE app_id = :appId AND usage_date = :date", nativeQuery = true)
    int incrementUsage(@Param("appId") Long appId, @Param("date") LocalDate date,
                       @Param("messages") long messages, @Param("tokens") long tokens);

    // ===== 用量看板聚合（该实体不继承 BaseTenantEntity，不受 Hibernate 租户过滤影响，where 手写）=====

    /**
     * 按天聚合调用/token/消息；tenantId/appId 为 null 时该维度不过滤。
     * 注：SysApiUsage 不继承 BaseTenantEntity，无 Hibernate 过滤器干扰，JPQL 手写 where 即可。
     */
    @Query(value = """
            SELECT u.usageDate AS date, SUM(u.callCount) AS calls,
                   SUM(u.tokenCount) AS tokens, SUM(u.messageCount) AS messages
            FROM SysApiUsage u
            WHERE u.usageDate >= :from
              AND (:tenantId IS NULL OR u.tenantId = :tenantId)
              AND (:appId IS NULL OR u.appId = :appId)
            GROUP BY u.usageDate
            ORDER BY u.usageDate
            """)
    List<UsageDailyRow> aggregateDaily(
            @Param("from") LocalDate from,
            @Param("tenantId") Long tenantId,
            @Param("appId") Long appId);

    /**
     * 平台视角：按租户排行（调用数降序）。用 Pageable 取 Top N。
     */
    @Query("""
            SELECT u.tenantId AS refId, SUM(u.callCount) AS calls, SUM(u.tokenCount) AS tokens
            FROM SysApiUsage u
            WHERE u.usageDate >= :from
            GROUP BY u.tenantId
            ORDER BY SUM(u.callCount) DESC
            """)
    List<UsageRankRow> rankByTenant(
            @Param("from") LocalDate from, Pageable pageable);

    /**
     * 租户视角：本租户内按应用排行。
     */
    @Query("""
            SELECT u.appId AS refId, SUM(u.callCount) AS calls, SUM(u.tokenCount) AS tokens
            FROM SysApiUsage u
            WHERE u.usageDate >= :from AND u.tenantId = :tenantId
            GROUP BY u.appId
            ORDER BY SUM(u.callCount) DESC
            """)
    List<UsageRankRow> rankByApp(
            @Param("from") LocalDate from, @Param("tenantId") Long tenantId,
            Pageable pageable);
}
