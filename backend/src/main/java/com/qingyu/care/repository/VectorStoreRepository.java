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
}
