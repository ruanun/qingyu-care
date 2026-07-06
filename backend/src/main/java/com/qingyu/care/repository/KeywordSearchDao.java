package com.qingyu.care.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class KeywordSearchDao {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

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
                        parseMetadata(rs.getString("metadata_json"), tenantId)),
                query, tenantId, query, limit);
    }

    /** 解析 vector_store.metadata 的 JSON 文本；解析失败兜底为仅含 tenantId，避免单行坏数据拖垮整路检索。 */
    private Map<String, Object> parseMetadata(String metadataJson, String tenantId) {
        if (metadataJson == null) {
            return Map.of("tenantId", tenantId);
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("关键词检索命中的 metadata 解析失败，兜底为仅含 tenantId tenantId={}", tenantId, e);
            return Map.of("tenantId", tenantId);
        }
    }
}
