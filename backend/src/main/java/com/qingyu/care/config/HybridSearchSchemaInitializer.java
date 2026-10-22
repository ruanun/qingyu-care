package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 混合检索表结构初始化：给 Spring AI 运行时建的 vector_store 加中文 FTS 生成列 + GIN 索引。
 * ApplicationRunner 在上下文就绪后运行（此时 Spring AI 已建好 vector_store），幂等可重复启动。
 *
 * @author ruan
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class HybridSearchSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese_zh') THEN
                            CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
                            ALTER TEXT SEARCH CONFIGURATION chinese_zh
                                ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;
                        END IF;
                    END
                    $$;""");
            jdbcTemplate.execute("""
                    ALTER TABLE vector_store
                        ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('chinese_zh', content)) STORED""");
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv
                        ON vector_store USING GIN (content_tsv)""");
            log.info("混合检索：vector_store.content_tsv 生成列与 GIN 索引已就绪");
        } catch (Exception e) {
            log.warn("混合检索表结构初始化失败，关键词检索可能不可用", e);
        }
    }
}
