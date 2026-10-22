package com.qingyu.care.it;

import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索护栏（关键词路）：三条同 appId/kb/tenant/docEnabled 的 chunk，仅 embeddingModel 不同
 * （当前模型 / 旧模型 / 未标该键），KeywordSearchDao.search 只应命中当前模型那一条——
 * 验证护栏拒绝式过滤真实生效（跨模型/未标向量被静默排除，不会混进检索结果）。
 * 仿 {@link KeywordSearchDaoIT} 播种手法，直接向 vector_store 播种（embedding 用零向量）。
 *
 * @author ruan
 */
class KeywordSearchEmbeddingModelFilterIT extends AbstractPostgresIT {

    private static final String T1 = "70003";

    @Autowired KeywordSearchDao keywordSearchDao;
    @Autowired JdbcTemplate jdbcTemplate;

    /** embeddingModel 为 null 时不写该键（模拟历史未打标行）；非 null 则写入等值键。 */
    private void seedChunk(String content, String embeddingModel) {
        String metadata = "{\"tenantId\":\"" + T1 + "\",\"appId\":\"80003\","
                + "\"knowledgeBaseId\":\"903\",\"visibility\":\"PUBLIC\",\"docEnabled\":\"true\""
                + (embeddingModel != null ? ",\"embeddingModel\":\"" + embeddingModel + "\"" : "")
                + "}";
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, metadata);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", T1);
    }

    @Test
    void 只命中当前embeddingModel标的向量_跨模型与未标向量被排除() {
        String currentModel = "qwen3.7-text-embedding";
        seedChunk("护栏产品型号XE-9000 当前模型说明", currentModel);
        seedChunk("护栏产品型号XE-9000 旧模型说明", "old-model");
        seedChunk("护栏产品型号XE-9000 未标模型说明", null);

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "护栏产品型号",
                new RetrievalScope(70003L, 80003L, List.of(903L), DocumentVisibility.INTERNAL),
                currentModel, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("当前模型说明");
    }
}
