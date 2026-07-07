package com.qingyu.care.repository;

import com.qingyu.care.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    
    List<KnowledgeBase> findByTenantIdAndEnabledTrue(Long tenantId);

    List<KnowledgeBase> findByEnabledTrue();

    /** 显式按 appId 过滤（不只依赖 TenantDataAspect 隐式过滤——appId 隔离双重保险第一道）。 */
    List<KnowledgeBase> findByAppIdAndEnabledTrue(Long appId);
}
