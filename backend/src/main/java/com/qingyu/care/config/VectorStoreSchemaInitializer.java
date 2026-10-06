package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 维护 {@code vector_store} 的向量列与 HNSW 索引，并校验列维度与配置一致。
 *
 * <p>此前靠 Spring AI 自动装配的 {@code initialize-schema} 建表，但 2.0 起它默认关闭，而生产 yml 没开；
 * 同时 {@code VectorStoreEntity} 把这张表映射成了 JPA 实体（不含 embedding 列）——全新库上表由 Hibernate
 * 先建出来、<b>没有 embedding 列</b>，第一次写向量才炸。现在向量模型改由后台配置、自动装配已关，
 * 表结构统一在这里维护：表已存在就补列，列已存在就校验维度。
 *
 * <p>与 {@link HybridSearchSchemaInitializer} 相反，这里 <b>fail-fast</b>：维度对不上时任何向量都写不进去、
 * 检索也全部报错，带病启动只会把问题推迟到第一次上传文档才暴露。
 *
 * @author ruan
 */
@Component
@Order(-1)
@Slf4j
public class VectorStoreSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final int dimensions;

    public VectorStoreSchemaInitializer(JdbcTemplate jdbcTemplate,
                                        @Value("${qingyu.care.embedding.dimensions:1024}") int dimensions) {
        this.jdbcTemplate = jdbcTemplate;
        this.dimensions = dimensions;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vector_store (
                    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
                    content text,
                    metadata json,
                    embedding vector(%d))""".formatted(dimensions));
        jdbcTemplate.execute("ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS embedding vector(%d)".formatted(dimensions));

        // 列已存在时 ADD COLUMN IF NOT EXISTS 不会改它的维度，必须单独核对
        String type = jdbcTemplate.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                WHERE a.attrelid = 'vector_store'::regclass AND a.attname = 'embedding' AND NOT a.attisdropped""",
                String.class);
        String expected = "vector(" + dimensions + ")";
        if (!expected.equals(type)) {
            throw new IllegalStateException("vector_store.embedding 列类型为 " + type + "，与配置的向量维度 "
                    + expected + " 不一致（qingyu.care.embedding.dimensions / AI_EMBEDDING_DIMENSIONS）。"
                    + "维度不能热切换：要么改回原值，要么清空 vector_store 后重建该列并重新摄取全部文档");
        }
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
                    ON vector_store USING hnsw (embedding vector_cosine_ops)""");
        log.info("vector_store 向量列与 HNSW 索引已就绪（{} 维）", dimensions);
    }
}
