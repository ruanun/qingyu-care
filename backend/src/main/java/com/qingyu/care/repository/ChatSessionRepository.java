package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.projection.SessionDailyRow;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    
    Optional<ChatSession> findBySessionId(String sessionId);
    
    List<ChatSession> findByUserId(String userId);
    
    List<ChatSession> findByStatus(SessionStatus status);
    
    Page<ChatSession> findByAgentId(Long agentId, Pageable pageable);
    
    List<ChatSession> findByUserIdAndStatus(String userId, SessionStatus status);
    
    // ===== 新增方法 =====
    
    /**
     * 分页查询用户的历史会话
     */
    Page<ChatSession> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    
    /**
     * 分页查询所有会话（带状态过滤）
     */
    Page<ChatSession> findByStatusOrderByCreatedAtDesc(SessionStatus status, Pageable pageable);
    
    /**
     * 分页查询所有会话
     */
    Page<ChatSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ===== 应用级 list 平台管理员越权收口（批次 B）=====
    // TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，裸 findAllByOrderByCreatedAtDesc /
    // findStatusOrderByCreatedAtDesc / findByUserIdOrderByCreatedAtDesc 会让平台管理员跨租户读会话。
    // 服务层显式按 TenantContext.getTenantId() 收窄，下面三个派生方法配合使用。

    /**
     * 分页查询指定租户的所有会话
     */
    Page<ChatSession> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    /**
     * 分页查询指定租户下指定状态的会话
     */
    Page<ChatSession> findByTenantIdAndStatusOrderByCreatedAtDesc(
            Long tenantId, SessionStatus status, Pageable pageable);

    /**
     * 分页查询指定租户下指定用户的历史会话（防 userId 猜测跨租户读取）
     */
    Page<ChatSession> findByTenantIdAndUserIdOrderByCreatedAtDesc(
            Long tenantId, String userId, Pageable pageable);

    /**
     * 查找超时的活跃会话（最后更新时间超过指定时间）
     */
    @Query("SELECT s FROM ChatSession s WHERE s.status IN :activeStatuses AND s.updatedAt < :timeout")
    List<ChatSession> findTimeoutSessions(
            @Param("activeStatuses") List<SessionStatus> activeStatuses,
            @Param("timeout") LocalDateTime timeout);

    /**
     * 查找指定租户的超时活跃会话（配置了租户级超时覆盖 tenant.session.timeout.minutes 时使用）
     */
    @Query("SELECT s FROM ChatSession s WHERE s.status IN :activeStatuses AND s.updatedAt < :timeout "
            + "AND s.tenantId = :tenantId")
    List<ChatSession> findTimeoutSessionsForTenant(
            @Param("activeStatuses") List<SessionStatus> activeStatuses,
            @Param("timeout") LocalDateTime timeout,
            @Param("tenantId") Long tenantId);

    /**
     * 查找超时活跃会话，但排除有租户级超时覆盖的租户（它们由 findTimeoutSessionsForTenant 单独按各自阈值扫描）
     */
    @Query("SELECT s FROM ChatSession s WHERE s.status IN :activeStatuses AND s.updatedAt < :timeout "
            + "AND (s.tenantId IS NULL OR s.tenantId NOT IN :excludedTenantIds)")
    List<ChatSession> findTimeoutSessionsExcludingTenants(
            @Param("activeStatuses") List<SessionStatus> activeStatuses,
            @Param("timeout") LocalDateTime timeout,
            @Param("excludedTenantIds") List<Long> excludedTenantIds);
    
    /**
     * 批量更新会话状态为超时关闭
     */
    @Modifying
    @Query("UPDATE ChatSession s SET s.status = :newStatus, s.endedAt = :endTime WHERE s.id IN :ids")
    int batchUpdateStatus(
            @Param("ids") List<Long> ids,
            @Param("newStatus") SessionStatus newStatus,
            @Param("endTime") LocalDateTime endTime);
    
    // ===== 统计方法 =====
    
    /**
     * 统计指定状态的会话数
     */
    long countByStatus(SessionStatus status);
    
    /**
     * 统计多个状态的会话总数
     */
    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.status IN :statuses")
    long countByStatusIn(@Param("statuses") List<SessionStatus> statuses);
    
    /**
     * 统计今日创建的会话数
     */
    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.createdAt >= :startOfDay")
    long countTodaySessions(@Param("startOfDay") LocalDateTime startOfDay);
    
    /**
     * 统计已结束且无人工介入的会话数（AI 自动解决）
     */
    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.status = 'ENDED' AND s.agentId IS NULL")
    long countAiResolvedSessions();
    
    /**
     * 统计所有已结束的会话数
     */
    @Query("SELECT COUNT(s) FROM ChatSession s WHERE s.status = 'ENDED'")
    long countEndedSessions();

    // ===== 用量看板会话聚合 =====
    // chat_session 继承 BaseTenantEntity，受 Hibernate tenantFilter/appFilter 约束。
    // Hibernate 过滤器只作用于 JPQL/Criteria，不作用于 native SQL——故这里用 nativeQuery
    // 绕过过滤器、手写 tenant_id 条件，确保「租户级跨应用汇总」不被请求头 X-App-Id 挡到单应用。

    /**
     * 按天会话数（native 绕过过滤器）。
     */
    @Query(value = """
            SELECT CAST(created_at AS DATE) AS date, COUNT(*) AS sessions
            FROM chat_session
            WHERE created_at >= :from
              AND (:tenantId IS NULL OR tenant_id = :tenantId)
              AND (:appId IS NULL OR app_id = :appId)
            GROUP BY CAST(created_at AS DATE)
            ORDER BY CAST(created_at AS DATE)
            """, nativeQuery = true)
    List<SessionDailyRow> aggregateDailySessions(
            @Param("from") LocalDateTime from,
            @Param("tenantId") Long tenantId,
            @Param("appId") Long appId);

    /**
     * 区间汇总：总数 / 已结束 / AI 解决（ENDED 且无人工 agent_id）/ 转人工（handover_time 非空）。
     * native 查询绕过 Hibernate 租户过滤器，手写 tenant_id/app_id 条件确保跨应用汇总正确。
     * 注：native 列别名 → projection 的映射不区分大小写，PostgreSQL 折叠大小写后 aiResolved
     * 列名仍能匹配到 SessionSummaryRow.getAiResolved()。
     */
    @Query(value = """
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN status = 'ENDED' THEN 1 ELSE 0 END), 0) AS ended,
                   COALESCE(SUM(CASE WHEN status = 'ENDED' AND agent_id IS NULL THEN 1 ELSE 0 END), 0) AS aiResolved,
                   COALESCE(SUM(CASE WHEN handover_time IS NOT NULL THEN 1 ELSE 0 END), 0) AS handover
            FROM chat_session
            WHERE created_at >= :from
              AND (:tenantId IS NULL OR tenant_id = :tenantId)
              AND (:appId IS NULL OR app_id = :appId)
            """, nativeQuery = true)
    SessionSummaryRow aggregateSessionSummary(
            @Param("from") LocalDateTime from,
            @Param("tenantId") Long tenantId,
            @Param("appId") Long appId);
}

