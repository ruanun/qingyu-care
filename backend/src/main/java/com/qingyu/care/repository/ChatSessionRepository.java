package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
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
    
    /**
     * 查找超时的活跃会话（最后更新时间超过指定时间）
     */
    @Query("SELECT s FROM ChatSession s WHERE s.status IN :activeStatuses AND s.updatedAt < :timeout")
    List<ChatSession> findTimeoutSessions(
            @Param("activeStatuses") List<SessionStatus> activeStatuses,
            @Param("timeout") LocalDateTime timeout);
    
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
}

