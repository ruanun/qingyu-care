package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 关键词全文检索（zhparser 中文分词）DAO。直接查 Spring AI 的 vector_store，
 * 按 metadata.tenantId 隔离（与向量路 filterExpression 同源）。vector_store 非 Hibernate 实体，手写 SQL。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class KeywordSearchDao {

    private final JdbcTemplate jdbcTemplate;

    /** 一条命中：id 为 vector_store 主键（与向量路 Document.id 同源，供 RRF 对齐）。 */
    public record KeywordHit(String id, String content, Map<String, Object> metadata) {
    }

    /**
     * 按中文关键词全文检索，返回按 ts_rank_cd 降序的命中（仅本租户）。
     * plainto_tsquery 把查询串分词后按 AND 组合；无命中返回空列表。
     */
    public List<KeywordHit> search(String query, String tenantId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id::text AS id, content, metadata::text AS metadata_json
                FROM vector_store
                WHERE content_tsv @@ plainto_tsquery('chinese_zh', ?)
                  AND metadata ->> 'tenantId' = ?
                ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('chinese_zh', ?)) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new KeywordHit(
                        rs.getString("id"),
                        rs.getString("content"),
                        Map.of("tenantId", tenantId)),
                query, tenantId, query, limit);
    }
}
