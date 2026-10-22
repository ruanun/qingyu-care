package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 存量 chunk metadata 幂等回填：检索条件为等值判断（docEnabled=='true'、visibility=='PUBLIC'），
 * 缺键的存量行会被静默筛掉，故启动时一次性补默认键；顺带收尾历史债——摄取时 appId
 * 缺上下文写成空串的 chunk 按 documentId 反查 document 表回填 appId/tenantId。
 * 幂等（WHERE 只命中缺键/坏值行），失败仅 warn 不阻断启动，下次启动自动重试。
 *
 * @author ruan
 */
@Component
@Order(1)   // 排在 HybridSearchSchemaInitializer(@Order(0)) 之后
@RequiredArgsConstructor
@Slf4j
public class DocMetadataBackfillRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Spring AI PgVectorStore 建的 metadata 列是 json（非 jsonb）：jsonb_set 只接受 jsonb 入参，
            // 函数调用走 implicit cast context 不会自动转换，须显式 ::jsonb；结果赋回 json 列走
            // UPDATE SET 的 assignment cast context，jsonb -> json 无需再显式转换。
            int v = jdbcTemplate.update("""
                    UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{visibility}', '"PUBLIC"')
                    WHERE metadata ->> 'visibility' IS NULL""");
            int e = jdbcTemplate.update("""
                    UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{docEnabled}', '"true"')
                    WHERE metadata ->> 'docEnabled' IS NULL""");
            // 注意：jsonb_set 第三参为 NULL 时整个结果为 NULL——若不排除 tenant_id/app_id 为 NULL
            // 的 document 行，会把 chunk 的整行 metadata 静默置空且不可自愈，两列都必须防
            int a = jdbcTemplate.update("""
                    UPDATE vector_store v
                    SET metadata = jsonb_set(jsonb_set(v.metadata::jsonb, '{appId}', to_jsonb(d.app_id::text)),
                                             '{tenantId}', to_jsonb(d.tenant_id::text))
                    FROM document d
                    WHERE (v.metadata ->> 'appId' = '' OR v.metadata ->> 'appId' IS NULL)
                      AND v.metadata ->> 'documentId' = d.id::text
                      AND d.app_id IS NOT NULL
                      AND d.tenant_id IS NOT NULL""");
            log.info("文档 metadata 回填完成：visibility={} 行, docEnabled={} 行, appId 历史债={} 行", v, e, a);
            // 设计要求：反查不到（或 document 行缺 tenant/app）的孤儿 chunk 保持原样并记 warn 数量
            Integer orphans = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM vector_store v
                    WHERE (v.metadata ->> 'appId' = '' OR v.metadata ->> 'appId' IS NULL)""", Integer.class);
            if (orphans != null && orphans > 0) {
                log.warn("仍有 {} 个 chunk 的 appId 无法回填（documentId 反查不到或 document 行缺 tenant/app），保持原样", orphans);
            }
        } catch (Exception ex) {
            log.warn("文档 metadata 回填失败（不阻断启动，下次启动重试）", ex);
        }
    }
}
