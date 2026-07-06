package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 应用启动后：vector_store 应有 content_tsv 生成列与其 GIN 索引（幂等）。 */
class HybridSchemaInitializerIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 生成列与GIN索引启动后就绪() {
        Integer colCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_name='vector_store' AND column_name='content_tsv'",
                Integer.class);
        assertThat(colCount).isEqualTo(1);

        Integer idxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes " +
                        "WHERE tablename='vector_store' AND indexname='idx_vector_store_content_tsv'",
                Integer.class);
        assertThat(idxCount).isEqualTo(1);
    }
}
