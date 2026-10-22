package com.qingyu.care.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 向量存储 Repository（用于直接操作 vector_store 表）
 * 注意：Spring AI 使用自己的表，这里用原生 SQL 操作
 */
@Repository
public interface VectorStoreRepository extends JpaRepository<VectorStoreEntity, UUID> {
    
    /**
     * 根据 metadata 中的 documentId 删除向量记录
     */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    int deleteByDocumentId(@Param("documentId") String documentId);
    
    /**
     * 根据 metadata 中的 knowledgeBaseId 删除向量记录
     */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'knowledgeBaseId' = :knowledgeBaseId", nativeQuery = true)
    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);
    
    /**
     * 统计某文档的向量数量
     */
    @Query(value = "SELECT COUNT(*) FROM vector_store WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    long countByDocumentId(@Param("documentId") String documentId);

    /** 按 documentId 同步 chunk 的可见性标记（与文档行更新同事务）。metadata 列是 json 类型，须先转 jsonb。 */
    @Modifying
    @Query(value = "UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{visibility}', to_jsonb(:visibility)) "
            + "WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    int updateVisibilityByDocumentId(@Param("documentId") String documentId, @Param("visibility") String visibility);

    /** 按 documentId 同步 chunk 的启用标记（禁用不删 chunk，重新启用免重嵌入）。 */
    @Modifying
    @Query(value = "UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{docEnabled}', to_jsonb(:enabled)) "
            + "WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    int updateDocEnabledByDocumentId(@Param("documentId") String documentId, @Param("enabled") String enabled);

    /** 按 metadata 中的 qaPairId 删除向量（删除/重嵌 QA 前清旧）。 */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'qaPairId' = :qaPairId", nativeQuery = true)
    int deleteByQaPairId(@Param("qaPairId") String qaPairId);

    /** 按 qaPairId 同步 QA 向量的启用标记（停用不删向量，重新启用免重嵌入）。metadata 列是 json，须先转 jsonb。 */
    @Modifying
    @Query(value = "UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{docEnabled}', to_jsonb(:enabled)) "
            + "WHERE metadata->>'qaPairId' = :qaPairId", nativeQuery = true)
    int updateDocEnabledByQaPairId(@Param("qaPairId") String qaPairId, @Param("enabled") String enabled);

    // ===== 按向量模型标签操作（切换向量模型的影子重建用，见 EmbeddingMigrationService）=====

    /** 删除某文档在指定模型标签下的向量（只动目标模型那份，当前模型的向量原样保留、检索不受影响）。 */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'documentId' = :documentId "
            + "AND metadata->>'embeddingModel' = :tag", nativeQuery = true)
    int deleteByDocumentIdAndTag(@Param("documentId") String documentId, @Param("tag") String tag);

    /** 删除某 QA 在指定模型标签下的向量。 */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'qaPairId' = :qaPairId "
            + "AND metadata->>'embeddingModel' = :tag", nativeQuery = true)
    int deleteByQaPairIdAndTag(@Param("qaPairId") String qaPairId, @Param("tag") String tag);

    /** 删除某应用在指定标签下的全部向量（取消切换时清掉做了一半的目标模型向量）。 */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'appId' = :appId "
            + "AND metadata->>'embeddingModel' = :tag", nativeQuery = true)
    int deleteByAppIdAndTag(@Param("appId") String appId, @Param("tag") String tag);

    /**
     * 删除某应用<b>不属于</b>指定标签的全部向量（切换完成后清旧模型的向量）。
     * 判据是「不等于当前标签」而不是「等于旧标签」：也顺带清掉历次中断、取消留下的残余，可重复执行。
     * 标签缺失的老向量（metadata 无 embeddingModel）同样清掉——护栏本就检索不到它们。
     */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'appId' = :appId "
            + "AND (metadata->>'embeddingModel' IS NULL OR metadata->>'embeddingModel' <> :tag)", nativeQuery = true)
    int deleteByAppIdExceptTag(@Param("appId") String appId, @Param("tag") String tag);

    /**
     * 补漏扫描：某应用下「已完成、有切块」却在指定标签下一条向量都没有的文档 id。
     * 迁移任务收尾前用它找出漏网的（迁移开始后才完成首次处理、又恰好没赶上双写的文档）。
     */
    @Query(value = "SELECT d.id FROM document d WHERE d.app_id = :appId AND d.status = 'COMPLETED' "
            + "AND COALESCE(d.chunk_count, 0) > 0 AND NOT EXISTS (SELECT 1 FROM vector_store v "
            + "WHERE v.metadata->>'documentId' = CAST(d.id AS text) AND v.metadata->>'embeddingModel' = :tag)",
            nativeQuery = true)
    java.util.List<Long> findDocumentIdsMissingTag(@Param("appId") Long appId, @Param("tag") String tag);

    /** 补漏扫描：某应用下非草稿、却在指定标签下没有向量的 QA id。 */
    @Query(value = "SELECT q.id FROM qa_pair q WHERE q.app_id = :appId AND q.status IN ('ACTIVE', 'DISABLED') "
            + "AND NOT EXISTS (SELECT 1 FROM vector_store v "
            + "WHERE v.metadata->>'qaPairId' = CAST(q.id AS text) AND v.metadata->>'embeddingModel' = :tag)",
            nativeQuery = true)
    java.util.List<Long> findQaPairIdsMissingTag(@Param("appId") Long appId, @Param("tag") String tag);
}
