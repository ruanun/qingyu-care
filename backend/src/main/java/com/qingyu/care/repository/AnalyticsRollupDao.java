package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分析 rollup 聚合 DAO：native SQL 直写三张 stat 表（配合服务层先删后插保证幂等）。
 * 全部绕开 Hibernate 过滤器（stat 表非过滤实体；chat_* 表 native 查询不受租户过滤器影响）。
 * tenant_id/app_id 为 NULL 的历史脏数据行不参与统计（stat 表以两键为唯一键，NULL 行无归属意义）。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsRollupDao {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 咨询锁键空间划分：文档处理互斥用的是单参数（bigint）形式，键 = document id；这里用双参数
     * （int, int）形式，PostgreSQL 保证两种形式的键空间互不重叠。classId 常量区分用途，objId 为日期。
     */
    private static final int LOCK_CLASS_ROLLUP = 1;

    /**
     * 抢指定日期的聚合锁（事务级、非阻塞）。多实例同时到点时只有一个能拿到，另一个跳过本轮
     * （安全审查 L12，2026-09-03）：先删后插不是幂等的并发操作，两个实例各跑一遍会双写 stat 表。
     * 必须在事务内调用——事务结束锁自动释放。
     */
    public boolean tryLockDay(LocalDate day) {
        Boolean locked = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                LOCK_CLASS_ROLLUP, (int) day.toEpochDay());
        return Boolean.TRUE.equals(locked);
    }

    /** 幂等清理：删除指定日期的三表已有行 */
    public void deleteDay(LocalDate day) {
        jdbcTemplate.update("DELETE FROM stat_quality_daily WHERE stat_date = ?", day);
        jdbcTemplate.update("DELETE FROM stat_hot_keyword_daily WHERE stat_date = ?", day);
        jdbcTemplate.update("DELETE FROM stat_doc_hit_daily WHERE stat_date = ?", day);
    }

    /**
     * 质量日聚合：消息侧与会话侧分别按（租户,应用）分组后 FULL JOIN——
     * 某组当日可能只有消息或只有会话，两侧都不能丢。
     */
    public int insertQuality(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        return jdbcTemplate.update("""
                INSERT INTO stat_quality_daily (tenant_id, app_id, stat_date,
                    ai_msg_count, avg_confidence, low_confidence_count, fallback_count,
                    question_count, hit_question_count, feedback_up, feedback_down,
                    session_count, handover_count, resolved_count, ended_count)
                SELECT COALESCE(m.tenant_id, s.tenant_id), COALESCE(m.app_id, s.app_id), ?,
                       COALESCE(m.ai_msg_count, 0), m.avg_confidence, COALESCE(m.low_confidence_count, 0),
                       COALESCE(m.fallback_count, 0), COALESCE(m.question_count, 0),
                       COALESCE(m.hit_question_count, 0), COALESCE(m.feedback_up, 0), COALESCE(m.feedback_down, 0),
                       COALESCE(s.session_count, 0), COALESCE(s.handover_count, 0),
                       COALESCE(s.resolved_count, 0), COALESCE(s.ended_count, 0)
                FROM (
                    SELECT cs.tenant_id, cs.app_id,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score IS NOT NULL) AS ai_msg_count,
                           AVG(cm.confidence_score) FILTER (WHERE cm.role = 'ASSISTANT') AS avg_confidence,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score < 0.5) AS low_confidence_count,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.fallback = TRUE) AS fallback_count,
                           COUNT(*) FILTER (WHERE cm.role = 'USER') AS question_count,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.hit_document_ids IS NOT NULL) AS hit_question_count,
                           COUNT(*) FILTER (WHERE cm.feedback = 1) AS feedback_up,
                           COUNT(*) FILTER (WHERE cm.feedback = -1) AS feedback_down
                    FROM chat_message cm
                    JOIN chat_session cs ON cm.session_id = cs.id
                    WHERE cm.created_at >= ? AND cm.created_at < ?
                      AND cs.tenant_id IS NOT NULL AND cs.app_id IS NOT NULL
                    GROUP BY cs.tenant_id, cs.app_id
                ) m
                FULL JOIN (
                    SELECT tenant_id, app_id,
                           COUNT(*) AS session_count,
                           COUNT(*) FILTER (WHERE handover_time IS NOT NULL) AS handover_count,
                           COUNT(*) FILTER (WHERE status = 'ENDED' AND agent_id IS NULL) AS resolved_count,
                           COUNT(*) FILTER (WHERE status = 'ENDED') AS ended_count
                    FROM chat_session
                    WHERE created_at >= ? AND created_at < ?
                      AND tenant_id IS NOT NULL AND app_id IS NOT NULL
                    GROUP BY tenant_id, app_id
                ) s ON m.tenant_id = s.tenant_id AND m.app_id = s.app_id
                """, day, start, end, start, end);
    }

    /**
     * 热词日聚合：zhparser（chinese_zh，与混合检索同源配置）对当日用户提问分词，
     * unnest(tsvector) 展开词元 → 组内窗口取 TopN。丢弃单字词与超长词（列宽 100）。
     */
    public int insertHotKeywords(LocalDate day, int topN) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        return jdbcTemplate.update("""
                INSERT INTO stat_hot_keyword_daily (tenant_id, app_id, stat_date, keyword, freq)
                SELECT tenant_id, app_id, ?, lexeme, freq FROM (
                    SELECT cs.tenant_id, cs.app_id, t.lexeme, COUNT(*) AS freq,
                           ROW_NUMBER() OVER (PARTITION BY cs.tenant_id, cs.app_id
                                              ORDER BY COUNT(*) DESC, t.lexeme) AS rn
                    FROM chat_message cm
                    JOIN chat_session cs ON cm.session_id = cs.id
                    CROSS JOIN LATERAL unnest(to_tsvector('chinese_zh', cm.content)) AS t
                    WHERE cm.role = 'USER' AND cm.created_at >= ? AND cm.created_at < ?
                      AND cs.tenant_id IS NOT NULL AND cs.app_id IS NOT NULL
                      AND length(t.lexeme) BETWEEN 2 AND 100
                    GROUP BY cs.tenant_id, cs.app_id, t.lexeme
                ) x WHERE rn <= ?
                """, day, start, end, topN);
    }

    /** 文档命中日聚合：hit_document_ids JSON 数组展开计数 */
    public int insertDocHits(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        return jdbcTemplate.update("""
                INSERT INTO stat_doc_hit_daily (tenant_id, app_id, stat_date, document_id, hit_count)
                SELECT cs.tenant_id, cs.app_id, ?, d.doc_id::bigint, COUNT(*)
                FROM chat_message cm
                JOIN chat_session cs ON cm.session_id = cs.id
                CROSS JOIN LATERAL json_array_elements_text(cm.hit_document_ids::json) AS d(doc_id)
                WHERE cm.role = 'ASSISTANT' AND cm.hit_document_ids IS NOT NULL
                  AND cm.created_at >= ? AND cm.created_at < ?
                  AND cs.tenant_id IS NOT NULL AND cs.app_id IS NOT NULL
                GROUP BY cs.tenant_id, cs.app_id, d.doc_id
                """, day, start, end);
    }
}
