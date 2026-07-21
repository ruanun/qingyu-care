package com.qingyu.care.repository;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.repository.projection.DocIdNameRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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
}
