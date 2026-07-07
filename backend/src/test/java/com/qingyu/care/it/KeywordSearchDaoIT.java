package com.qingyu.care.it;

import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 关键词 FTS DAO：中文分词命中 + 仅命中本租户。直接向 vector_store 播种（embedding 用零向量）。 */
class KeywordSearchDaoIT extends AbstractPostgresIT {

    private static final String T1 = "70001", T2 = "70002";

    @Autowired KeywordSearchDao keywordSearchDao;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 带完整 scope 元数据的种子（appId/knowledgeBaseId 摄取时即写入，见 KnowledgeServiceImpl）。 */
    private void seedChunkWithScope(String content, String tenantId, String appId, String kbId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + tenantId + "\",\"appId\":\"" + appId
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' IN (?,?)", T1, T2);
    }

    @Test
    void 中文关键词命中且仅本租户() {
        seedChunkWithScope("轻语客服支持产品型号XZ-9000的售后咨询", T1, "80001", "901");
        seedChunkWithScope("其他无关内容", T1, "80001", "901");
        seedChunkWithScope("他租户也有产品型号XZ-9000", T2, "80001", "901");   // 他租户，不应命中

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new RetrievalScope(70001L, 80001L, List.of(901L)),
                10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("XZ-9000");
    }

    @Test
    void 无命中返回空() {
        seedChunkWithScope("完全不相关的文本", T1, "80001", "901");
        assertThat(keywordSearchDao.search(
                "量子纠缠",
                new RetrievalScope(70001L, 80001L, List.of(901L)),
                10)).isEmpty();
    }

    @Test
    void scope过滤_仅命中启用库与本应用() {
        seedChunkWithScope("库一 产品型号XK-1111 说明", T1, "80001", "901");
        seedChunkWithScope("库二 产品型号XK-1111 说明", T1, "80001", "902");   // 不在 kbIds，不应命中
        seedChunkWithScope("他应用 产品型号XK-1111 说明", T1, "80002", "901"); // 他应用，不应命中

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new RetrievalScope(70001L, 80001L, List.of(901L)),
                10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("库一");
    }

    @Test
    void scope过滤_多库IN联合命中() {
        seedChunkWithScope("库一 产品型号XK-1111", T1, "80001", "901");
        seedChunkWithScope("库二 产品型号XK-1111", T1, "80001", "902");

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new RetrievalScope(70001L, 80001L, List.of(901L, 902L)),
                10);

        assertThat(hits).hasSize(2);
    }
}
