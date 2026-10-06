package com.qingyu.care.repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.service.RetrievalScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词全文检索（zhparser 中文分词）DAO。直接查 Spring AI 的 vector_store，
 * 按 metadata 的 appId + knowledgeBaseId IN 过滤（双重保险），docEnabled 恒等值过滤、
 * visibility 在 PUBLIC 级别下等值过滤，tenantId 子句在有租户上下文时附加
 * （与向量路 RetrievalScope.toFilterExpression 同源）。vector_store 非 Hibernate 实体，手写 SQL。
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
     * 按检索范围（应用 + enabled 知识库集合 + 租户 + 访问级别）做中文关键词全文检索。
     * appId/kbIds 双重保险闭合应用隔离缺口；tenantId 为 null 时省略该子句（同向量路语义）；
     * accessLevel 为 PUBLIC 时额外要求 visibility == 'PUBLIC'，INTERNAL/null 不加该子句（全见）。
     * 仅在非空范围下调用（调用方已短路空范围）。
     */
    public List<KeywordHit> search(String query, RetrievalScope scope, String embeddingTag, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id::text AS id, content, metadata::text AS metadata_json FROM vector_store " +
                "WHERE content_tsv @@ plainto_tsquery('chinese_zh', ?) " +
                "AND metadata ->> 'appId' = ? ");
        params.add(query);
        params.add(String.valueOf(scope.appId()));
        sql.append("AND metadata ->> 'knowledgeBaseId' IN (")
           .append(String.join(",", Collections.nCopies(scope.kbIds().size(), "?")))
           .append(") ");
        for (Long kbId : scope.kbIds()) {
            params.add(String.valueOf(kbId));
        }
        // 等值条件（摄取写全键 + 启动回填保证键存在；缺键行做 <> 判断会被 SQL NULL 语义静默筛掉，故不用）
        sql.append("AND metadata ->> 'docEnabled' = 'true' ");
        // 护栏：只匹配当前 embedding 模型嵌的向量，跨模型/未标向量静默排除（与向量路 toFilterExpression 同源）
        sql.append("AND metadata ->> 'embeddingModel' = ? ");
        params.add(embeddingTag);
        if (scope.accessLevel() == DocumentVisibility.PUBLIC) {
            sql.append("AND metadata ->> 'visibility' = 'PUBLIC' ");
        }
        if (scope.tenantId() != null) {
            sql.append("AND metadata ->> 'tenantId' = ? ");
            params.add(String.valueOf(scope.tenantId()));
        }
        sql.append("ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('chinese_zh', ?)) DESC LIMIT ?");
        params.add(query);
        params.add(limit);
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new KeywordHit(
                        rs.getString("id"),
                        rs.getString("content"),
                        parseMetadata(rs.getString("metadata_json"), scope)),
                params.toArray());
    }

    /** scope 版兜底：解析失败时保留 scope 中已知的隔离键，避免单行坏数据拖垮整路检索。 */
    private Map<String, Object> parseMetadata(String metadataJson, RetrievalScope scope) {
        if (metadataJson != null) {
            try {
                return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("关键词检索命中的 metadata 解析失败，兜底为 scope 隔离键 appId={}", scope.appId(), e);
            }
        }
        Map<String, Object> fallback = new HashMap<>();
        if (scope.tenantId() != null) {
            fallback.put("tenantId", String.valueOf(scope.tenantId()));
        }
        fallback.put("appId", String.valueOf(scope.appId()));
        return fallback;
    }
}
