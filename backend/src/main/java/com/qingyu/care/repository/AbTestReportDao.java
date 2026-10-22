package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A/B 实验报表聚合 DAO（native 绕过滤器；入参 experimentId 须先经 JPA getById 校验归属）。
 * 指标口径与 AnalyticsRollupDao.insertQuality 保持一致（低置信 <0.5、AI 回复含置信度非空等）。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class AbTestReportDao {

    private final JdbcTemplate jdbcTemplate;

    /** 会话级变体聚合行 */
    public record SessionRow(String variantName, long sessionCount, long handoverCount,
                             long resolvedCount, long endedCount) {}

    /** 消息级变体聚合行 */
    public record MessageRow(String variantName, long questionCount, long aiMsgCount,
                             Double avgConfidence, long lowConfidenceCount, long fallbackCount,
                             long hitCount, long feedbackUp, long feedbackDown) {}

    public List<SessionRow> aggregateSessions(Long experimentId) {
        return jdbcTemplate.query("""
                SELECT variant_name,
                       COUNT(*) AS session_count,
                       COUNT(*) FILTER (WHERE handover_time IS NOT NULL) AS handover_count,
                       COUNT(*) FILTER (WHERE status = 'ENDED' AND agent_id IS NULL) AS resolved_count,
                       COUNT(*) FILTER (WHERE status = 'ENDED') AS ended_count
                FROM chat_session
                WHERE experiment_id = ? AND variant_name IS NOT NULL
                GROUP BY variant_name
                """,
                (rs, i) -> new SessionRow(rs.getString("variant_name"), rs.getLong("session_count"),
                        rs.getLong("handover_count"), rs.getLong("resolved_count"),
                        rs.getLong("ended_count")),
                experimentId);
    }

    public List<MessageRow> aggregateMessages(Long experimentId) {
        return jdbcTemplate.query("""
                SELECT cs.variant_name,
                       COUNT(*) FILTER (WHERE cm.role = 'USER') AS question_count,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score IS NOT NULL) AS ai_msg_count,
                       AVG(cm.confidence_score) FILTER (WHERE cm.role = 'ASSISTANT') AS avg_confidence,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score < 0.5) AS low_confidence_count,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.fallback = TRUE) AS fallback_count,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.hit_document_ids IS NOT NULL) AS hit_count,
                       COUNT(*) FILTER (WHERE cm.feedback = 1) AS feedback_up,
                       COUNT(*) FILTER (WHERE cm.feedback = -1) AS feedback_down
                FROM chat_message cm
                JOIN chat_session cs ON cm.session_id = cs.id
                WHERE cs.experiment_id = ? AND cs.variant_name IS NOT NULL
                GROUP BY cs.variant_name
                """,
                (rs, i) -> new MessageRow(rs.getString("variant_name"), rs.getLong("question_count"),
                        rs.getLong("ai_msg_count"), (Double) rs.getObject("avg_confidence"),
                        rs.getLong("low_confidence_count"), rs.getLong("fallback_count"),
                        rs.getLong("hit_count"), rs.getLong("feedback_up"), rs.getLong("feedback_down")),
                experimentId);
    }
}
