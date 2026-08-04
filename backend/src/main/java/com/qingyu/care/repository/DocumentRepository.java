package com.qingyu.care.repository;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.repository.projection.DocIdNameRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByKnowledgeBaseId(Long knowledgeBaseId);

    List<Document> findByStatus(DocumentStatus status);

    List<Document> findByKnowledgeBaseIdAndStatus(Long knowledgeBaseId, DocumentStatus status);

    // ===== 覆盖率分析（native 绕过租户过滤器，where 手写）=====

    /** 窗口内零命中的启用文档（最多 50 条；status 字面量以 DocumentStatus 实际枚举为准） */
    @Query(value = """
            SELECT d.id AS id, d.name AS name FROM document d
            WHERE (:tenantId IS NULL OR d.tenant_id = :tenantId)
              AND (:appId IS NULL OR d.app_id = :appId)
              AND (d.enabled IS NULL OR d.enabled = TRUE)
              AND d.status = 'COMPLETED'
              AND NOT EXISTS (
                  SELECT 1 FROM stat_doc_hit_daily h
                  WHERE h.document_id = d.id AND h.stat_date >= :from)
            ORDER BY d.id
            LIMIT 50
            """, nativeQuery = true)
    List<DocIdNameRow> findZeroHitDocs(@Param("from") LocalDate from,
                                       @Param("tenantId") Long tenantId,
                                       @Param("appId") Long appId);

    /** 按 id 批量取文档名（native 免过滤器：排行含跨应用文档时名称仍可回填） */
    @Query(value = "SELECT d.id AS id, d.name AS name FROM document d WHERE d.id IN (:ids)", nativeQuery = true)
    List<DocIdNameRow> findNamesByIds(@Param("ids") List<Long> ids);

    /**
     * 查到期需要自动同步的网页文档 id。
     *
     * <p><b>必须用原生 SQL</b>：调度器线程无 TenantContext，普通 JPA 查询会被
     * Hibernate @Filter 用 tenant_id = -1 拒绝一切、静默返回空集（任务照常跑却什么都不同步）。
     * 这与多级配置回退取平台默认行是同一个坑，做法一致。
     *
     * <p>到期条件：类型为 URL、周期非 MANUAL、且（从未同步过 或 距上次成功同步已超过周期）。
     *
     * <p>{@code :now} 显式 {@code CAST(... AS timestamp)}：JDBC 占位符不带类型信息，
     * 直接写 {@code :now - INTERVAL '1 hour'} 时 PostgreSQL 会把参数解析成 interval
     * 而非 timestamp，导致 {@code timestamp <= interval} 报「operator does not exist」；
     * 显式转型消除歧义。
     *
     * <p>⚠️ {@code SELECT d.id} <b>必须写成 {@code SELECT d.id AS id}</b>：Hibernate ORM 7.4.1
     * （Boot 4.1 迁移引入）对 native 查询里裸露、且列名与某实体 {@code @Id} 字段同名的单列
     * SELECT，会启发式判定为「实体标识符查询」并把该实体当前启用的 Hibernate @Filter 注入到
     * 生成 SQL 里（实测会拼出 {@code WHERE ... AND tenant_id = ? AND app_id = ?}），
     * 使 native 绕过过滤器彻底失效、静默变回被过滤——比本方法原本要防的坑更隐蔽，因为连
     * {@code nativeQuery = true} 都保留着，肉眼看代码完全看不出问题。加显式列别名后
     * Hibernate 不再触发该启发式判定，与本文件 {@link #findZeroHitDocs} / {@link #findNamesByIds}
     * 一直沿用的「native 查询列必须显式 AS 别名」写法一致。
     */
    @Query(value = """
            SELECT d.id AS id FROM document d
            WHERE d.document_type = 'URL'
              AND d.sync_interval IS NOT NULL
              AND d.sync_interval <> 'MANUAL'
              AND (
                    d.last_synced_at IS NULL
                 OR (d.sync_interval = 'HOURLY' AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '1 hour')
                 OR (d.sync_interval = 'DAILY'  AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '1 day')
                 OR (d.sync_interval = 'WEEKLY' AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '7 days')
              )
            ORDER BY d.last_synced_at NULLS FIRST, d.id
            """, nativeQuery = true)
    List<Long> findDueWebPageIds(@Param("now") LocalDateTime now);
}
