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
     * 查到期需要自动同步的网页文档 id（最多 50 条，逻辑同 {@link #findZeroHitDocs}）。
     *
     * <p>用原生 SQL 绕过 Hibernate 租户过滤器，与本文件 {@link #findZeroHitDocs}/
     * {@link #findNamesByIds} 手法一致——但对调度器线程而言这是<b>纵深防御</b>而非唯一活路：
     * 调度器线程没有 Sa-Token 会话，{@code TenantDataAspect} 在「未登录」分支直接 return、
     * 根本不会启用任何过滤器（见该类第 49 行 {@code !isLoggedIn()} 分支），所以就算写成
     * 普通 JPQL，当前实现下也一样能查到全部租户的到期文档；{@code tenant_id = -1}
     * 那一支只在「已登录的非管理员却缺租户上下文」时才会触发，不是调度器线程会走到的路径。
     * 选 native 是为了不依赖「调度器线程永远没有 Sa-Token 会话」这个前提本身——万一将来
     * 这段逻辑被复用到某个带会话的上下文里，也不会静默退化成被过滤，而不至于重蹈本方法本身
     * 曾经历过的教训（见下方 IT 里记录的变异自证过程）。
     *
     * <p>到期条件：类型为 URL、周期非 MANUAL、且（从未同步过 或 距上次成功同步已超过周期）。
     *
     * <p>{@code :now} 显式 {@code CAST(... AS timestamp)}：JDBC 占位符不带类型信息，
     * 直接写 {@code :now - INTERVAL '1 hour'} 时 PostgreSQL 会把参数解析成 interval
     * 而非 timestamp，导致 {@code timestamp <= interval} 报「operator does not exist」；
     * 显式转型消除歧义。
     *
     * <p>{@code LIMIT 50}：单线程调度池（Spring Boot 默认 {@code spring.task.scheduling.pool.size=1}）
     * 上同时挂着 webhook 队列轮询、在线坐席心跳等其它定时任务，若到期文档过多、逐篇
     * {@link com.qingyu.care.service.web.WebPageSyncService#syncDocument} 又是同步 HTTP 抓取，
     * 无上界会长时间占住这唯一线程；每小时一轮，超出上限的留到下一轮，不丢数据。
     */
    @Query(value = """
            SELECT d.id AS id FROM document d
            WHERE d.document_type = 'URL'
              AND d.sync_interval <> 'MANUAL'
              AND (
                    d.last_synced_at IS NULL
                 OR (d.sync_interval = 'HOURLY' AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '1 hour')
                 OR (d.sync_interval = 'DAILY'  AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '1 day')
                 OR (d.sync_interval = 'WEEKLY' AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '7 days')
              )
            ORDER BY d.last_synced_at NULLS FIRST, d.id
            LIMIT 50
            """, nativeQuery = true)
    List<Long> findDueWebPageIds(@Param("now") LocalDateTime now);
}
