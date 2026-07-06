package com.qingyu.care.it;

import com.qingyu.care.repository.KeywordSearchDao;
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

    /**
     * 向 vector_store 插一行；embedding 用零向量（array_fill 造，避免巨型字面量）。
     * 维度取 test profile 实际配置的 1536（application-test.yml），而非生产 1024（text-embedding-v3）。
     */
    private void seedChunk(String content, String tenantId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + tenantId + "\"}");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' IN (?,?)", T1, T2);
    }

    @Test
    void 中文关键词命中且仅本租户() {
        seedChunk("轻语客服支持产品型号XZ-9000的售后咨询", T1);
        seedChunk("其他无关内容", T1);
        seedChunk("他租户也有产品型号XZ-9000", T2);   // 他租户，不应命中

        List<KeywordSearchDao.KeywordHit> hits =
                keywordSearchDao.search("产品型号", T1, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("XZ-9000");
    }

    @Test
    void 无命中返回空() {
        seedChunk("完全不相关的文本", T1);
        assertThat(keywordSearchDao.search("量子纠缠", T1, 10)).isEmpty();
    }
}
