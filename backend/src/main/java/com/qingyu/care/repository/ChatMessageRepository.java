package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.repository.projection.HotQuestionRow;
import com.qingyu.care.repository.projection.MissedQuestionRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<ChatMessage> findTop20BySessionIdOrderByCreatedAtDesc(Long sessionId);

    /** 会话内最新一条消息（按 id 降序取第一条），用于坐席接管瞬间置顶已读水位线。 */
    Optional<ChatMessage> findTopBySessionIdOrderByIdDesc(Long sessionId);

    // ===== 分析三件套实时明细（native 绕过租户过滤器，where 手写；同 ChatSessionRepository 聚合惯例）=====

    /** 高频问句 TopN（同内容精确归组；含当日实时数据） */
    @Query(value = """
            SELECT cm.content AS content, COUNT(*) AS cnt
            FROM chat_message cm
            JOIN chat_session cs ON cm.session_id = cs.id
            WHERE cm.role = 'USER' AND cm.created_at >= :from
              AND (:tenantId IS NULL OR cs.tenant_id = :tenantId)
              AND (:appId IS NULL OR cs.app_id = :appId)
            GROUP BY cm.content
            ORDER BY COUNT(*) DESC, cm.content
            LIMIT :limit
            """, nativeQuery = true)
    List<HotQuestionRow> topQuestions(@Param("from") LocalDateTime from,
                                      @Param("tenantId") Long tenantId,
                                      @Param("appId") Long appId,
                                      @Param("limit") int limit);

    /**
     * 未命中提问明细：AI 回复无命中（hit_document_ids IS NULL 且置信度非空=真 AI 回复）
     * 对应的最近一条用户提问（LEFT JOIN LATERAL 取同会话中 id 更小的最后一条 USER 消息）。
     */
    @Query(value = """
            SELECT COALESCE(u.content, '(无对应提问)') AS content,
                   a.created_at AS createdAt, cs.session_id AS sessionId
            FROM chat_message a
            JOIN chat_session cs ON a.session_id = cs.id
            LEFT JOIN LATERAL (
                SELECT m.content FROM chat_message m
                WHERE m.session_id = a.session_id AND m.role = 'USER' AND m.id < a.id
                ORDER BY m.id DESC LIMIT 1
            ) u ON TRUE
            WHERE a.role = 'ASSISTANT' AND a.confidence_score IS NOT NULL
              AND a.hit_document_ids IS NULL AND a.created_at >= :from
              AND (:tenantId IS NULL OR cs.tenant_id = :tenantId)
              AND (:appId IS NULL OR cs.app_id = :appId)
            ORDER BY a.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM chat_message a
            JOIN chat_session cs ON a.session_id = cs.id
            WHERE a.role = 'ASSISTANT' AND a.confidence_score IS NOT NULL
              AND a.hit_document_ids IS NULL AND a.created_at >= :from
              AND (:tenantId IS NULL OR cs.tenant_id = :tenantId)
              AND (:appId IS NULL OR cs.app_id = :appId)
            """, nativeQuery = true)
    Page<MissedQuestionRow> findMissedQuestions(@Param("from") LocalDateTime from,
                                                @Param("tenantId") Long tenantId,
                                                @Param("appId") Long appId,
                                                Pageable pageable);
}
