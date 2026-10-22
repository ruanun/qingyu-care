package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 租户已占用的对象存储字节数：知识库文件 + 聊天附件的 {@code file_size} 之和。
 *
 * <p>⚠️ <b>必须原生 SQL 且显式带 tenant_id 条件</b>：{@code Document} 与 {@code ChatAttachment}
 * 都继承 {@code BaseTenantEntity}，JPQL 聚合会受 Hibernate {@code @Filter} 影响，而配额检查也可能
 * 发生在没有租户上下文的路径上（同 {@code AgentSessionDao} / {@code AnalyticsRollupDao} 的既有模式）。
 *
 * <p><b>口径说明</b>：只统计真正占用对象存储的行。爬取与单页抓取产生的 URL 类文档
 * {@code file_size} 为 null（正文存在库里、S3 上没有对象），{@code COALESCE} 后计 0；
 * 那条链路的用量由站点爬取自身的页数硬顶（{@code max-pages-limit}，500）约束，不纳入存储配额。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class StorageUsageDao {

    private static final String SQL = """
            SELECT COALESCE((SELECT SUM(file_size) FROM document WHERE tenant_id = ?), 0)
                 + COALESCE((SELECT SUM(file_size) FROM chat_attachment WHERE tenant_id = ?), 0)
            """;

    private final JdbcTemplate jdbcTemplate;

    /** @param tenantId 租户 id，调用方保证非 null */
    public long sumBytes(Long tenantId) {
        Long v = jdbcTemplate.queryForObject(SQL, Long.class, tenantId, tenantId);
        return v == null ? 0L : v;
    }
}
