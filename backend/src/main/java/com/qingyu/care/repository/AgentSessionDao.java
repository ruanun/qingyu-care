package com.qingyu.care.repository;

import com.qingyu.care.dto.AgentSessionDTO;
import com.qingyu.care.enums.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 坐席「我的进行中会话」及其未读数聚合查询。
 *
 * <p>⚠️ <b>必须原生 SQL 且显式带 tenant_id / app_id 条件</b>：{@code ChatSession} 的
 * Hibernate {@code @Filter} 保护不到 JOIN 聚合查询，且 {@code TenantDataAspect} 对
 * PLATFORM_ADMIN 整体不启用过滤器。同 {@code getWaitingAgentSessions} 已有的手工收窄，
 * 以及 {@code AnalyticsRollupDao} / {@code DocumentRepository.findDueWebPageIds} 的既有模式。
 * 漏掉这个条件的后果是跨租户读到别人的会话——本类有一条 IT 用变异验证过这一点。
 *
 * <p>⚠️ 排序键用 {@code MAX(m.created_at)} 而非 {@code chat_session.updated_at}：后者会被与
 * 消息无关的更新（打标签、改摘要、转接）带动，排序会莫名跳动，与「有新消息的会话浮到顶部」
 * 这个语义不一致，也和未读徽章不是同一套口径。
 *
 * <p>未读数只统计 {@code role = 'USER'} 且 id 超过已读水位线的消息（坐席自己发的不算未读）；
 * 从无回执时用 {@code COALESCE(up_to_message_id, 0)} 兜底，此时未读数等于全部访客消息数。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class AgentSessionDao {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL = """
            SELECT s.id, s.session_id, s.user_id, s.status,
                   MAX(m.created_at) AS last_message_at,
                   COUNT(*) FILTER (
                       WHERE m.role = 'USER'
                         AND m.id > COALESCE(r.up_to_message_id, 0)
                   ) AS unread_count
              FROM chat_session s
              LEFT JOIN chat_message m ON m.session_id = s.id
              LEFT JOIN message_read_receipt r
                     ON r.session_id = s.id
                    AND r.reader_type = 'AGENT'
                    AND r.reader_id = ?
             WHERE s.status = 'AGENT_PROCESSING'
               AND s.agent_id = ?
               AND s.tenant_id = ?
               AND (? IS NULL OR s.app_id = ?)
             GROUP BY s.id, s.session_id, s.user_id, s.status, r.up_to_message_id
             ORDER BY last_message_at DESC NULLS LAST
             LIMIT ? OFFSET ?
            """;

    /**
     * 查询指定坐席当前接管中的会话，含每个会话的未读消息数，按最后消息时间倒序。
     *
     * @param tenantId 当前租户 id，绝不能为 null（调用方须提前判空返空列表，见
     *                 {@code ChatServiceImpl.getMySessions}）
     * @param appId    当前应用 id，可为 null（不按应用收窄，兼容管理端无 appId 场景）
     * @param agentId  当前坐席 user id，由控制器取好传入
     */
    public List<AgentSessionDTO> findMySessions(Long tenantId, Long appId, Long agentId,
                                                int limit, int offset) {
        return jdbcTemplate.query(SQL,
                (rs, i) -> new AgentSessionDTO(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("user_id"),
                        SessionStatus.valueOf(rs.getString("status")),
                        rs.getObject("last_message_at", LocalDateTime.class),
                        rs.getLong("unread_count")),
                agentId, agentId, tenantId, appId, appId, limit, offset);
    }
}
