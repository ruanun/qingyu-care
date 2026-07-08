package com.qingyu.care.it;

import com.qingyu.care.config.DocMetadataBackfillRunner;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存量 chunk 回填：缺 visibility/docEnabled 键补默认值；appId 空串按 documentId 反查 document 表回填。
 * runner 幂等（二次执行无副作用）。不用类级 @Transactional：runner 直接 JdbcTemplate 更新，
 * 种子与断言同线程同连接即可，@AfterEach 显式清理。
 *
 * @author ruan
 */
class DocMetadataBackfillIT extends AbstractPostgresIT {

    private static final String TID = "62001";

    @Autowired DocMetadataBackfillRunner runner;
    @Autowired JdbcTemplate jdbcTemplate;

    private void seedRaw(String metadataJson) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), '回填测试内容', ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                metadataJson);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
        jdbcTemplate.update("DELETE FROM document WHERE tenant_id = ?", Long.valueOf(TID));
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ?", Long.valueOf(TID));
    }

    @Test
    void 缺键chunk_回填默认值_且幂等() {
        seedRaw("{\"tenantId\":\"" + TID + "\",\"appId\":\"62002\",\"knowledgeBaseId\":\"1\"}");

        runner.run(null);

        var row = jdbcTemplate.queryForMap(
                "SELECT metadata->>'visibility' AS v, metadata->>'docEnabled' AS e "
                        + "FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
        assertThat(row.get("v")).isEqualTo("PUBLIC");
        assertThat(row.get("e")).isEqualTo("true");

        runner.run(null);   // 幂等：二次执行不报错、值不变
        var again = jdbcTemplate.queryForMap(
                "SELECT metadata->>'visibility' AS v FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
        assertThat(again.get("v")).isEqualTo("PUBLIC");
    }

    @Test
    void appId空串chunk_按documentId反查document表回填() {
        // 先造 document 行（直接 SQL，绕过实体监听器，显式给 tenant/app）
        jdbcTemplate.update(
                "INSERT INTO knowledge_base(name, enabled, tenant_id, app_id) VALUES ('回填库', true, ?, ?)",
                Long.valueOf(TID), 62002L);
        Long kbId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_base WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, Long.valueOf(TID));
        jdbcTemplate.update(
                "INSERT INTO document(name, document_type, status, chunk_count, knowledge_base_id, tenant_id, app_id) "
                        + "VALUES ('回填文档', 'TEXT', 'COMPLETED', 1, ?, ?, ?)",
                kbId, Long.valueOf(TID), 62002L);
        Long docId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, Long.valueOf(TID));
        // 历史坏数据：appId 空串
        seedRaw("{\"tenantId\":\"" + TID + "\",\"appId\":\"\",\"documentId\":\"" + docId
                + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");

        runner.run(null);

        String appId = jdbcTemplate.queryForObject(
                "SELECT metadata->>'appId' FROM vector_store WHERE metadata->>'documentId' = ?",
                String.class, String.valueOf(docId));
        assertThat(appId).isEqualTo("62002");
    }
}
