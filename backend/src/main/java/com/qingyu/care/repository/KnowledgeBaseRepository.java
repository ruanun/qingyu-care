package com.qingyu.care.repository;

import com.qingyu.care.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    
    List<KnowledgeBase> findByTenantIdAndEnabledTrue(Long tenantId);
    
    List<KnowledgeBase> findByEnabledTrue();
}
