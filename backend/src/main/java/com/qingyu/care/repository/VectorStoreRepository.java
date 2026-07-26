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
}
