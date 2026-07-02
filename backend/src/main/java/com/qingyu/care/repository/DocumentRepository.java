package com.qingyu.care.repository;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    
    List<Document> findByKnowledgeBaseId(Long knowledgeBaseId);
    
    List<Document> findByStatus(DocumentStatus status);
    
    List<Document> findByKnowledgeBaseIdAndStatus(Long knowledgeBaseId, DocumentStatus status);
}
